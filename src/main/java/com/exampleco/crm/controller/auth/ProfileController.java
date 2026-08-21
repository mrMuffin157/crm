package com.exampleco.crm.controller.auth;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Map;
import com.exampleco.crm.domain.auth.S2UserAccount;
import com.exampleco.crm.repository.auth.S2UserAccountRepository;

// Self-service account management for the CURRENTLY LOGGED-IN user only — every method here
// resolves the target account from the session's Authentication, never from a path/body id, so
// there's no way for one account to edit another's through this controller (that's
// AppUserAdminController's job, and requires ROLE_ADMIN). Deliberately excludes s2user.password
// from every response — see S2UserAccount's own doc comment for why that column is never
// serialized.
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final S2UserAccountRepository s2UserAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public ProfileController(S2UserAccountRepository s2UserAccountRepository, PasswordEncoder passwordEncoder) {
        this.s2UserAccountRepository = s2UserAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public record ProfileResponse(String username, String email, Long telegramChatId, LocalDateTime dateCreated) {
    }

    public record UpdateProfileRequest(String email, Long telegramChatId) {
    }

    // Enter-and-re-enter shape, deliberately no "current password" field — this is the account
    // owner acting on their own already-authenticated session, not a password-reset flow for
    // someone else's account (AppUserAdminController never touches an existing password at all).
    public record ChangePasswordRequest(String newPassword, String confirmPassword) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ProfileResponse>> getProfile(Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() ->
                s2UserAccountRepository.findByUsername(authentication.getName())
                        .map(account -> ResponseEntity.ok(toProfile(account)))
                        .orElseGet(() -> ResponseEntity.notFound().build())
        ).subscribeOn(Schedulers.boundedElastic()));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> updateProfile(Mono<Authentication> authenticationMono, @RequestBody UpdateProfileRequest request) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            S2UserAccount account = s2UserAccountRepository.findByUsername(authentication.getName()).orElse(null);
            if (account == null) {
                return ResponseEntity.notFound().build();
            }
            account.setEmail(request.email());
            account.setTelegramChatId(request.telegramChatId());
            account.setLastUpdated(LocalDateTime.now());
            s2UserAccountRepository.save(account);
            return ResponseEntity.ok((Object) toProfile(account));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PatchMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> changePassword(Mono<Authentication> authenticationMono, @RequestBody ChangePasswordRequest request) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            String newPassword = request.newPassword() == null ? "" : request.newPassword();
            String confirmPassword = request.confirmPassword() == null ? "" : request.confirmPassword();
            if (!newPassword.equals(confirmPassword)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Passwords do not match"));
            }
            if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
            }
            S2UserAccount account = s2UserAccountRepository.findByUsername(authentication.getName()).orElse(null);
            if (account == null) {
                return ResponseEntity.notFound().build();
            }
            account.setPassword(passwordEncoder.encode(newPassword));
            account.setLastUpdated(LocalDateTime.now());
            s2UserAccountRepository.save(account);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private ProfileResponse toProfile(S2UserAccount account) {
        return new ProfileResponse(account.getUsername(), account.getEmail(), account.getTelegramChatId(), account.getDateCreated());
    }
}
