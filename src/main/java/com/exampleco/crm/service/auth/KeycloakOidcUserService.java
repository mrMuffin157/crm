package com.exampleco.crm.service.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import com.exampleco.crm.domain.auth.AppUserRole;
import com.exampleco.crm.domain.auth.S2UserAccount;
import com.exampleco.crm.repository.auth.AppUserRoleRepository;
import com.exampleco.crm.repository.auth.S2UserAccountRepository;

// Keycloak only ever authenticates who someone is; whether that person may use this app, and
// with what role, is still decided entirely by the real s2user table + this app's own
// app_user_role allowlist — the exact same rule AppUserDetailsService enforces for password
// login (see its doc comment) and the same rule legacy-app itself applies: no auto-sync of Keycloak
// realm roles, and a valid Keycloak identity with no granted app_user_role is rejected.
@Service
public class KeycloakOidcUserService implements ReactiveOAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcReactiveOAuth2UserService delegate = new OidcReactiveOAuth2UserService();
    private final S2UserAccountRepository s2UserAccountRepository;
    private final AppUserRoleRepository appUserRoleRepository;

    public KeycloakOidcUserService(S2UserAccountRepository s2UserAccountRepository,
                                    AppUserRoleRepository appUserRoleRepository) {
        this.s2UserAccountRepository = s2UserAccountRepository;
        this.appUserRoleRepository = appUserRoleRepository;
    }

    @Override
    public Mono<OidcUser> loadUser(OidcUserRequest userRequest) {
        return delegate.loadUser(userRequest)
                .flatMap(oidcUser -> resolveGrantedRole(oidcUser.getName())
                        .map(role -> (OidcUser) new DefaultOidcUser(
                                List.of(new SimpleGrantedAuthority("ROLE_" + role)),
                                oidcUser.getIdToken(),
                                oidcUser.getUserInfo(),
                                "preferred_username"))
                        .switchIfEmpty(Mono.error(new OAuth2AuthenticationException(new OAuth2Error(
                                "access_denied",
                                "No app_user_role granted for this account",
                                null)))));
    }

    // Same allowlist check as AppUserDetailsService.load(), minus the password/credentials-expired
    // checks that only make sense for the local password login path — Keycloak has already
    // vouched for the password.
    private Mono<String> resolveGrantedRole(String username) {
        return Mono.fromCallable(() -> lookupRole(username))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(role -> role == null ? Mono.empty() : Mono.just(role));
    }

    private String lookupRole(String username) {
        S2UserAccount account = s2UserAccountRepository.findByUsername(username).orElse(null);
        if (account == null
                || !Boolean.TRUE.equals(account.getEnabled())
                || Boolean.TRUE.equals(account.getAccountExpired())
                || Boolean.TRUE.equals(account.getAccountLocked())) {
            return null;
        }
        AppUserRole role = appUserRoleRepository.findById(account.getId()).orElse(null);
        return role == null ? null : role.getRole();
    }
}
