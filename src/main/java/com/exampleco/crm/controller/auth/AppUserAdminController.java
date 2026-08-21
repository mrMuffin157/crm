package com.exampleco.crm.controller.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.exampleco.crm.domain.auth.AppUserChannel;
import com.exampleco.crm.domain.auth.AppUserRole;
import com.exampleco.crm.domain.auth.Role;
import com.exampleco.crm.domain.auth.S2UserAccount;
import com.exampleco.crm.domain.ticket.TicketEnums;
import com.exampleco.crm.dto.auth.UserAccessSummary;
import com.exampleco.crm.dto.auth.UserCandidate;
import com.exampleco.crm.repository.auth.AppUserChannelRepository;
import com.exampleco.crm.repository.auth.AppUserRoleRepository;
import com.exampleco.crm.repository.auth.S2UserAccountRepository;
import com.exampleco.crm.service.auth.ChannelAccessService;

// Every endpoint here requires ROLE_ADMIN — enforced in SecurityConfig at the filter-chain
// level (a non-admin's request is rejected with 403 before it ever reaches this class), not
// just hidden in the frontend. The sidebar hiding the Admin section for members is a UX nicety
// on top of this, not the actual security boundary.
//
// Login credentials live entirely in the real s2user table now (see AppUserRole's doc comment)
// — this controller either grants THIS APP's access to an existing s2user account via
// app_user_role (grantAccess), creates a brand new s2user login outright (createUser), or resets
// an existing account's password (resetPassword) — all deliberate, explicit admin actions, same
// as BootstrapAdminSeeder's own bootstrap insert.
//
// Removing access comes in two strengths, both member-only (an admin must be demoted via
// updateRole first, which already enforces the last-admin rule, so neither of these needs its
// own): deactivateAccess only drops the app_user_role grant — the s2user login survives and the
// account reappears as a "grant access" candidate — while deleteUser removes the s2user row
// too, permanently, with no path back.
@RestController
@RequestMapping("/api/admin/users")
public class AppUserAdminController {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final AppUserRoleRepository appUserRoleRepository;
    private final S2UserAccountRepository s2UserAccountRepository;
    private final AppUserChannelRepository appUserChannelRepository;
    private final ChannelAccessService channelAccessService;
    private final PasswordEncoder passwordEncoder;

    public AppUserAdminController(AppUserRoleRepository appUserRoleRepository, S2UserAccountRepository s2UserAccountRepository,
                                   AppUserChannelRepository appUserChannelRepository, ChannelAccessService channelAccessService,
                                   PasswordEncoder passwordEncoder) {
        this.appUserRoleRepository = appUserRoleRepository;
        this.s2UserAccountRepository = s2UserAccountRepository;
        this.appUserChannelRepository = appUserChannelRepository;
        this.channelAccessService = channelAccessService;
        this.passwordEncoder = passwordEncoder;
    }

    public record GrantAccessRequest(String username) {
    }

    public record CreateUserRequest(String username, String password) {
    }

    public record UpdateRoleRequest(String role) {
    }

    public record ResetPasswordRequest(String newPassword) {
    }

    // channels: the ticket channels this account is explicitly granted via
    // app_user_channel — meaningless for an ADMIN row (an admin has every channel implicitly,
    // never stored here; see ChannelAccessService), so the frontend renders "All channels" for
    // those instead of this list.
    public record SetChannelsRequest(List<String> channels) {
    }

    // Same shape as UserAccessSummary, but a plain record: the single-row responses below are
    // built directly from an S2UserAccount + AppUserRole already in hand, not from the native
    // list query that projection interface backs.
    public record UserAccessResult(Long id, String username, String email, String name, String role,
                                    LocalDateTime dateCreated, List<String> channels) {
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<UserAccessResult>> listUsers() {
        return Mono.fromCallable(() -> {
            List<UserAccessSummary> summaries = appUserRoleRepository.findAllWithRole();
            List<Long> ids = summaries.stream().map(UserAccessSummary::getId).toList();
            Map<Long, List<String>> channelsByUserId = groupChannelsByUserId(
                    ids.isEmpty() ? List.of() : appUserChannelRepository.findByS2userIdIn(ids));
            return summaries.stream()
                    .map(s -> new UserAccessResult(s.getId(), s.getUsername(), s.getEmail(), s.getName(), s.getRole(),
                            s.getDateCreated(), channelsByUserId.getOrDefault(s.getId(), List.of())))
                    .toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Batched IN-clause grouping — same "enrich()" shape TicketController's own batched lookups
    // follow, not a per-row query.
    private Map<Long, List<String>> groupChannelsByUserId(List<AppUserChannel> grants) {
        Map<Long, List<String>> result = new HashMap<>();
        for (AppUserChannel grant : grants) {
            result.computeIfAbsent(grant.getS2userId(), k -> new ArrayList<>()).add(grant.getSourceSystem());
        }
        result.values().forEach(Collections::sort);
        return result;
    }

    // Real s2user accounts that don't have a role yet — the pool the "grant access" form picks
    // from, instead of free-typing a brand new username into a table this app doesn't own.
    @GetMapping(value = "/candidates", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<UserCandidate>> listCandidates() {
        return Mono.fromCallable(appUserRoleRepository::findCandidates).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> grantAccess(@RequestBody GrantAccessRequest request) {
        return Mono.fromCallable(() -> {
            String username = request.username() == null ? "" : request.username().trim();
            if (username.isBlank()) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "Username is required"));
            }
            S2UserAccount account = s2UserAccountRepository.findByUsername(username).orElse(null);
            if (account == null) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "No such user in s2user"));
            }
            if (appUserRoleRepository.existsById(account.getId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body((Object) Map.of("error", "That user already has access"));
            }
            AppUserRole role = new AppUserRole();
            role.setS2userId(account.getId());
            role.setRole(Role.MEMBER);
            role.setDateCreated(LocalDateTime.now());
            appUserRoleRepository.save(role);
            return ResponseEntity.ok((Object) toSummary(account, role, List.of()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Creates a brand new s2user login outright, for cases where no existing employee account
    // exists yet to grant access to — a real employee whose Grails login was never created, or
    // a non-employee collaborator who needs an account. Both new fields (username/password) are
    // this app's own to set, not something borrowed from an existing real login.
    @PostMapping(value = "/create", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> createUser(@RequestBody CreateUserRequest request) {
        return Mono.fromCallable(() -> {
            String username = request.username() == null ? "" : request.username().trim();
            String password = request.password() == null ? "" : request.password();
            if (username.isBlank()) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "Username is required"));
            }
            if (password.length() < MIN_PASSWORD_LENGTH) {
                return ResponseEntity.badRequest()
                        .body((Object) Map.of("error", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
            }
            if (s2UserAccountRepository.findByUsername(username).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body((Object) Map.of("error", "That username already exists"));
            }
            S2UserAccount account = new S2UserAccount();
            account.setUsername(username);
            account.setPassword(passwordEncoder.encode(password));
            account.setAccountExpired(false);
            account.setAccountLocked(false);
            account.setEnabled(true);
            account.setPasswordExpired(false);
            account.setTelegramChatId(0L);
            account.setDateCreated(LocalDateTime.now());
            account.setLastUpdated(LocalDateTime.now());
            account = s2UserAccountRepository.save(account);

            AppUserRole role = new AppUserRole();
            role.setS2userId(account.getId());
            role.setRole(Role.MEMBER);
            role.setDateCreated(LocalDateTime.now());
            appUserRoleRepository.save(role);

            return ResponseEntity.ok((Object) toSummary(account, role, List.of()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Demoting/promoting only ever changes the database row — a session already in progress
    // for that user keeps the authorities it had at login time until they log out and back
    // in. Worth knowing: a just-demoted admin isn't instantly locked out of an open tab.
    @PatchMapping(value = "/{id}/role", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> updateRole(@PathVariable Long id, @RequestBody UpdateRoleRequest request) {
        return Mono.fromCallable(() -> {
            AppUserRole role = appUserRoleRepository.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.notFound().build();
            }
            String newRole = request.role();
            if (!Role.ADMIN.equals(newRole) && !Role.MEMBER.equals(newRole)) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "role must be ADMIN or MEMBER"));
            }
            if (Role.MEMBER.equals(newRole) && Role.ADMIN.equals(role.getRole()) && appUserRoleRepository.countByRole(Role.ADMIN) <= 1) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body((Object) Map.of("error", "Cannot remove the last admin — promote someone else first"));
            }
            role.setRole(newRole);
            appUserRoleRepository.save(role);
            S2UserAccount account = s2UserAccountRepository.findById(id).orElse(null);
            List<String> channels = new ArrayList<>(appUserChannelRepository.findSourceSystemsByS2userId(id));
            Collections.sort(channels);
            return ResponseEntity.ok((Object) toSummary(account, role, channels));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Replaces a member's whole channel-grant set — delete-then-reinsert; this table
    // isn't @Audited, so there's no audit-noise reason to diff against what was there before the
    // way TicketController#persistLineItems now does. Admin-only rows are rejected outright: an admin already has
    // every channel implicitly (see ChannelAccessService), so this table is never written to for
    // one — demote to member first if channels genuinely need restricting.
    @PatchMapping(value = "/{id}/channels", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> updateChannels(@PathVariable Long id, @RequestBody SetChannelsRequest request) {
        return Mono.fromCallable(() -> {
            AppUserRole role = appUserRoleRepository.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.notFound().build();
            }
            if (Role.ADMIN.equals(role.getRole())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body((Object) Map.of("error", "Admins already have access to every channel — demote to member first to restrict channels"));
            }
            List<String> requested = request.channels() == null ? List.of() : request.channels();
            List<String> unknown = requested.stream().filter(c -> !TicketEnums.SOURCE_SYSTEM_CHANNELS.contains(c)).toList();
            if (!unknown.isEmpty()) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "Unknown channel(s): " + String.join(", ", unknown)));
            }
            List<String> distinct = requested.stream().distinct().sorted().toList();
            channelAccessService.replaceChannels(id, distinct);
            S2UserAccount account = s2UserAccountRepository.findById(id).orElse(null);
            return ResponseEntity.ok((Object) toSummary(account, role, distinct));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Admin-driven password reset for an existing account — e.g. a locked-out user who can't
    // reach ProfileController's own self-service change because they can't log in in the first
    // place. Same encoder as everywhere else, so this also self-heals a legacy account still
    // carrying the "{bcrypt}" prefix bug (see doc/0.3.1/fix_s2user_bcrypt_prefix.sql).
    @PatchMapping(value = "/{id}/password", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest request) {
        return Mono.fromCallable(() -> {
            String newPassword = request.newPassword() == null ? "" : request.newPassword();
            if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                return ResponseEntity.badRequest()
                        .body((Object) Map.of("error", "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
            }
            S2UserAccount account = s2UserAccountRepository.findById(id).orElse(null);
            if (account == null) {
                return ResponseEntity.notFound().build();
            }
            account.setPassword(passwordEncoder.encode(newPassword));
            account.setLastUpdated(LocalDateTime.now());
            s2UserAccountRepository.save(account);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Deactivates this app's access — deletes only the app_user_role row, so the account drops
    // back into the "grant access" candidate pool for later reinstatement. The underlying
    // s2user account (which may be a real employee) is never touched or deleted. Members only —
    // an admin must be demoted first (via updateRole, which already enforces the last-admin
    // rule), so there's no separate last-admin check needed here.
    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> deactivateAccess(@PathVariable Long id, Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            AppUserRole role = appUserRoleRepository.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.notFound().build();
            }
            S2UserAccount account = s2UserAccountRepository.findById(id).orElse(null);
            if (account != null && authentication.getName().equals(account.getUsername())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body((Object) Map.of("error", "Cannot deactivate your own access while logged in as it"));
            }
            if (Role.ADMIN.equals(role.getRole())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body((Object) Map.of("error", "Demote to member first"));
            }
            appUserRoleRepository.delete(role);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // Permanently deletes the account outright — both the app_user_role grant AND the
    // underlying s2user row. Unlike deactivation, this is NOT reversible from the candidate
    // pool: the real login is gone. Members only, same reasoning as deactivateAccess — an admin
    // must be demoted first. A real employee's s2user row can be referenced elsewhere (e.g.
    // ticket.handler_id), so the delete can fail on a foreign-key constraint; that's surfaced as
    // a clear error rather than a raw 500.
    @DeleteMapping(value = "/{id}/permanent", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> deleteUser(@PathVariable Long id, Mono<Authentication> authenticationMono) {
        return authenticationMono.flatMap(authentication -> Mono.fromCallable(() -> {
            AppUserRole role = appUserRoleRepository.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.notFound().build();
            }
            S2UserAccount account = s2UserAccountRepository.findById(id).orElse(null);
            if (account != null && authentication.getName().equals(account.getUsername())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body((Object) Map.of("error", "Cannot delete your own account while logged in as it"));
            }
            if (Role.ADMIN.equals(role.getRole())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body((Object) Map.of("error", "Demote to member first"));
            }
            // Delete s2user FIRST: if a foreign-key constraint rejects it (e.g. this account is
            // still a ticket's handler), nothing has been touched yet — app_user_role is only
            // removed once the harder delete has already succeeded, so a failed attempt never
            // leaves the account half-deleted.
            if (account != null) {
                try {
                    s2UserAccountRepository.delete(account);
                } catch (DataIntegrityViolationException e) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body((Object) Map.of("error", "Cannot delete — this account is still referenced elsewhere (e.g. as a ticket handler)"));
                }
            }
            appUserRoleRepository.delete(role);
            return ResponseEntity.ok().build();
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    // grantAccess/createUser/updateRole/resetPassword can each hit a constraint violation
    // (e.g. a duplicate username race) that isn't caught locally like deleteUser's own — same
    // reasoning as TicketController#handleDataIntegrityViolation, surfaced here too instead of
    // leaking a raw 500.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid request: a required field is missing or references a value that doesn't exist."));
    }

    private UserAccessResult toSummary(S2UserAccount account, AppUserRole role, List<String> channels) {
        return new UserAccessResult(account.getId(), account.getUsername(), account.getEmail(), account.getName(),
                role.getRole(), role.getDateCreated(), channels);
    }
}
