package com.exampleco.crm.service.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

// Authenticates legacy-app's scheduled jobs against this app's ticket M2M API — the inbound mirror
// of LegacyApiClient's outbound refresh-token exchange. legacy-app redeems its refresh token for a
// short-lived JWT at LegacyOauthController's POST /oauth/access_token, then presents that JWT
// here as its Bearer credential; LegacyServiceTokenService.verify() checks signature + exp +
// subject, mirroring the low-ceremony trust this app already extends to legacy-app in the other
// direction (LegacyApiClient decoding legacy-app's own JWT exp claim without a signature check,
// since legacy-app is the one verifying there).
//
// Wired into SecurityConfig at SecurityWebFiltersOrder.AUTHENTICATION, after the session-based
// security context loads and before authorizeExchange runs. It only ever overrides that context
// when a matching bearer token is actually present, so it never interferes with ordinary
// cookie-authenticated requests from the CRM frontend.
//
// Deliberately NOT a @Component: any WebFilter bean in the context gets auto-registered into
// WebFlux's own global filter chain as well as wherever it's explicitly added, so this is
// constructed directly inside SecurityConfig instead — it must run only inside Spring
// Security's chain, at the specific position addFilterAt gives it.
public class LegacyServiceAuthenticationFilter implements WebFilter {

    // Referenced by SecurityConfig's authorizeExchange rules to gate the legacy-app-only endpoints
    // once they exist.
    public static final String AUTHORITY = "ROLE_SERVICE";

    private static final String BEARER_PREFIX = "Bearer ";

    private final LegacyServiceTokenService tokenService;

    public LegacyServiceAuthenticationFilter(LegacyServiceTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String presentedToken = header.substring(BEARER_PREFIX.length());
        Optional<String> subject = tokenService.verify(presentedToken);
        if (subject.isEmpty()) {
            return chain.filter(exchange);
        }

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                subject.get(), null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
