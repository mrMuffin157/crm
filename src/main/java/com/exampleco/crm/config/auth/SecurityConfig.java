package com.exampleco.crm.config.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;
import com.exampleco.crm.domain.auth.Role;
import com.exampleco.crm.service.auth.LegacyServiceAuthenticationFilter;
import com.exampleco.crm.service.auth.LegacyServiceTokenService;

// This app runs on WebFlux (reactive/Netty), not the servlet stack — so this uses Spring
// Security's reactive API (ServerHttpSecurity, ReactiveUserDetailsService, WebSession), not
// the more commonly-documented servlet one (HttpSecurity, UserDetailsService, HttpSession).
// Session-cookie authentication over a JSON API (login via AuthController, not Spring
// Security's default form-login page) — appropriate for a small internal tool with a React
// SPA frontend, not a public-facing product. See README's security notes for the deliberate
// trade-offs made here (no CSRF protection, no login rate limiting).
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final String allowedOrigin;
    private final boolean keycloakEnabled;

    public SecurityConfig(@Value("${app.cors.allowed-origins}") String allowedOrigin,
                           KeycloakProperties keycloakProperties) {
        this.allowedOrigin = allowedOrigin;
        this.keycloakEnabled = keycloakProperties.enabled();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ReactiveAuthenticationManager reactiveAuthenticationManager(ReactiveUserDetailsService userDetailsService,
                                                                        PasswordEncoder passwordEncoder) {
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    @Bean
    public ServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                           ServerSecurityContextRepository securityContextRepository,
                                                           LegacyServiceTokenService legacyServiceTokenService) {
        http
                .securityContextRepository(securityContextRepository)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Authenticates legacy-app's JWT-bearing service requests (see
                // LegacyServiceAuthenticationFilter) — runs after the session context above loads
                // and before authorizeExchange below, so a valid Bearer token satisfies
                // anyExchange().authenticated() on its own, with no session required. Not a
                // @Component: it must only run here, at this exact chain position, not also as a
                // separately auto-registered global WebFilter.
                .addFilterAt(new LegacyServiceAuthenticationFilter(legacyServiceTokenService), SecurityWebFiltersOrder.AUTHENTICATION)
                // CSRF matters for cookie-authenticated state-changing requests from a browser,
                // but this app has no other users to protect against besides its own small,
                // trusted team — disabling it is a deliberate simplification, not an oversight.
                // Revisit if this ever gets exposed beyond a handful of trusted employees.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/auth/login", "/api/auth/config").permitAll()
                        // legacy-app has no session yet when it redeems its refresh token for a JWT
                        // here (see LegacyOauthController) — the JWT itself is what's checked on
                        // every subsequent call, by LegacyServiceAuthenticationFilter above.
                        .pathMatchers(HttpMethod.POST, "/oauth/access_token").permitAll()
                        // Only ever reachable when app.keycloak.enabled=true wires .oauth2Login()
                        // below — harmless to leave permitted otherwise, since with no such
                        // filter registered there's nothing behind these paths to reach anyway.
                        .pathMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // The built React SPA (frontend/dist) is bundled into this jar's static
                        // resources and served from this same origin — see Dockerfile stage 2.
                        // Its shell must be reachable before authentication, or there is no way
                        // to ever load the login form itself. The SPA's own JS then calls the
                        // already-public /api/auth/** endpoints from there.
                        .pathMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                        .pathMatchers("/api/admin/**").hasRole(Role.ADMIN)
                        // Close-ticket CSV import is routine CS work, not an admin
                        // action, so it's deliberately not under /api/admin/**. A bare
                        // anyExchange().authenticated() below would still let it through, but
                        // that would also hand it to legacy-app's ROLE_SERVICE Bearer JWT — a
                        // cross-channel bulk-close capability that caller has never had and
                        // shouldn't gain by accident. This explicit rule must stay ahead of
                        // anyExchange() below.
                        .pathMatchers(HttpMethod.POST, "/api/tickets/close-import").hasAnyRole(Role.ADMIN, Role.MEMBER)
                        // Disabling review for an order or a buyer is a CS agent's
                        // judgement call, and it is recorded against that agent's own username on
                        // legacy-app's side (see SalesOrderController's own updateBy comment). Same
                        // reasoning as close-import above: bare authentication would hand these
                        // to legacy-app's ROLE_SERVICE Bearer JWT, which has no agent identity to
                        // record and no business suppressing its own review pipeline through CRM.
                        .pathMatchers(HttpMethod.POST, "/api/ticketApi/markReviewDisabled",
                                "/api/customerApi/markReviewDisabled").hasAnyRole(Role.ADMIN, Role.MEMBER)
                        // The "Export ticket report" CSV, for the third time the same reasoning:
                        // it is CS work, so not /api/admin/**, but bare authentication would hand
                        // legacy-app's ROLE_SERVICE Bearer JWT a dump of every open ManualHandling
                        // ticket's buyer name, email and shipping address across all 4 channels.
                        // The per-member channel scoping this export also needs is row-level and
                        // lives in TicketController#exportTicketReport, not here.
                        .pathMatchers(HttpMethod.GET, "/api/tickets/report-export").hasAnyRole(Role.ADMIN, Role.MEMBER)
                        .anyExchange().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN))
                );

        // Gated behind the toggle rather than always wiring oauth2Login with placeholder config —
        // no Keycloak realm/client exists for this app yet, and leaving it off means the existing
        // JSON login flow (AuthController) stays the only way in. When on, this populates the
        // same securityContextRepository set above, so AuthController.me() and every other
        // endpoint don't need to know which login method established the session.
        //
        // No explicit userService/oidcUserService wiring here: OAuth2LoginSpec resolves its OIDC
        // user service by looking up a ReactiveOAuth2UserService<OidcUserRequest, OidcUser> bean
        // from the context, which KeycloakOidcUserService already is (@Service) — that's where
        // the app_user_role allowlist check actually happens.
        if (keycloakEnabled) {
            http.oauth2Login(oauth2 -> oauth2
                    // The explicit exceptionHandling entry point above already keeps ordinary API
                    // 401s as plain JSON status codes, not a redirect — this only governs what
                    // happens when the Keycloak callback itself rejects the login (e.g. no
                    // app_user_role granted), sending the browser back to the SPA with a flag it
                    // can show an error for, mirroring legacy's UsernameNotFoundException handling.
                    .authenticationFailureHandler(new RedirectServerAuthenticationFailureHandler("/?keycloakError=1"))
            );
        }

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // Required for the session cookie to travel on cross-origin fetches (allowedOrigins
        // must stay an explicit list, never "*", once credentials are allowed).
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
