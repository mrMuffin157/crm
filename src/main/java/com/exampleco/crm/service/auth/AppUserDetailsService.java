package com.exampleco.crm.service.auth;

import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.exampleco.crm.domain.auth.AppUserRole;
import com.exampleco.crm.domain.auth.S2UserAccount;
import com.exampleco.crm.repository.auth.AppUserRoleRepository;
import com.exampleco.crm.repository.auth.S2UserAccountRepository;

// Reactive counterpart of the servlet-stack UserDetailsService — this app runs on WebFlux.
// Authenticates against the real s2user table; a valid s2user login isn't enough on its own —
// an AppUserRole row must also exist, since that's this app's own access allowlist (a real
// employee existing in s2user doesn't automatically mean they're allowed to use this tool).
@Service
public class AppUserDetailsService implements ReactiveUserDetailsService {

    private final S2UserAccountRepository s2UserAccountRepository;
    private final AppUserRoleRepository appUserRoleRepository;

    public AppUserDetailsService(S2UserAccountRepository s2UserAccountRepository, AppUserRoleRepository appUserRoleRepository) {
        this.s2UserAccountRepository = s2UserAccountRepository;
        this.appUserRoleRepository = appUserRoleRepository;
    }

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return Mono.fromCallable(() -> load(username))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> user == null ? Mono.empty() : Mono.just(user));
    }

    private UserDetails load(String username) {
        S2UserAccount account = s2UserAccountRepository.findByUsername(username).orElse(null);
        if (account == null) {
            return null;
        }
        AppUserRole role = appUserRoleRepository.findById(account.getId()).orElse(null);
        if (role == null) {
            // A real s2user account with no granted role means no access to this app.
            return null;
        }
        return User.builder()
                .username(account.getUsername())
                .password(account.getPassword())
                .disabled(!Boolean.TRUE.equals(account.getEnabled()))
                .accountExpired(Boolean.TRUE.equals(account.getAccountExpired()))
                .accountLocked(Boolean.TRUE.equals(account.getAccountLocked()))
                .credentialsExpired(Boolean.TRUE.equals(account.getPasswordExpired()))
                .authorities("ROLE_" + role.getRole())
                .build();
    }
}
