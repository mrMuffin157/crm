package com.exampleco.crm.controller.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import com.exampleco.crm.config.auth.KeycloakProperties;
import com.exampleco.crm.domain.auth.Role;
import com.exampleco.crm.service.auth.ChannelAccessService;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ReactiveAuthenticationManager authenticationManager;
    private final ServerSecurityContextRepository securityContextRepository;
    private final KeycloakProperties keycloakProperties;
    private final ChannelAccessService channelAccessService;

    public AuthController(ReactiveAuthenticationManager authenticationManager,
                           ServerSecurityContextRepository securityContextRepository,
                           KeycloakProperties keycloakProperties,
                           ChannelAccessService channelAccessService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.keycloakProperties = keycloakProperties;
        this.channelAccessService = channelAccessService;
    }

    public record LoginRequest(String username, String password) {
    }

    // allowedChannels: every ticket channel this user may see/create — the full
    // TicketEnums.SOURCE_SYSTEM_CHANNELS set for an admin, or exactly what's been explicitly
    // granted via app_user_channel for a member (see ChannelAccessService). The frontend uses
    // this to restrict the Ticket List's Sales Channel filter and the Create Ticket form's
    // Channel picker to what this user may actually use — the real enforcement is server-side
    // regardless (TicketController), this is UX only.
    public record CurrentUser(String username, String role, List<String> allowedChannels) {
    }

    // redirectUrl is null unless Keycloak is enabled — the frontend only navigates there when set.
    public record LogoutResponse(String redirectUrl) {
    }

    // Lets the SPA decide whether to render the local login form or redirect straight to
    // Keycloak — there's no server-rendered page here to branch on the flag server-side.
    public record AuthConfig(boolean keycloakEnabled) {
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<CurrentUser>> login(@RequestBody LoginRequest request, ServerWebExchange exchange) {
        return authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()))
                .flatMap(authentication -> securityContextRepository
                        .save(exchange, new SecurityContextImpl(authentication))
                        .thenReturn(ResponseEntity.ok(toCurrentUser(authentication))))
                .onErrorResume(BadCredentialsException.class,
                        e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()));
    }

    // Two-step logout when Keycloak is enabled: invalidate the local session first (same as
    // always), then hand the frontend Keycloak's own end-session URL to navigate to — a fetch()
    // response can't itself carry the browser to another origin the way a real navigation could.
    @PostMapping(value = "/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<LogoutResponse>> logout(ServerWebExchange exchange) {
        return exchange.getSession()
                .flatMap(session -> session.invalidate())
                .thenReturn(ResponseEntity.ok(new LogoutResponse(
                        keycloakProperties.enabled() ? keycloakLogoutUrl() : null)));
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<AuthConfig> config() {
        return Mono.just(new AuthConfig(keycloakProperties.enabled()));
    }

    private String keycloakLogoutUrl() {
        return UriComponentsBuilder.fromUriString(keycloakProperties.baseUrl())
                .path("/realms/{realm}/protocol/openid-connect/logout")
                .queryParam("client_id", keycloakProperties.clientId())
                .queryParam("post_logout_redirect_uri", keycloakProperties.postLogoutRedirectUri())
                .buildAndExpand(keycloakProperties.realm())
                .toUriString();
    }

    // Only ever reached when already authenticated — SecurityConfig requires authentication
    // for every path not explicitly permitted, and this one isn't, so an unauthenticated call
    // is rejected by the filter chain before it gets here.
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<CurrentUser>> me(Mono<Authentication> authenticationMono) {
        return authenticationMono
                .map(authentication -> ResponseEntity.ok(toCurrentUser(authentication)))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private CurrentUser toCurrentUser(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse(Role.MEMBER);
        List<String> allowedChannels = channelAccessService.resolveAllowedChannels(authentication).stream().sorted().toList();
        return new CurrentUser(authentication.getName(), role, allowedChannels);
    }
}
