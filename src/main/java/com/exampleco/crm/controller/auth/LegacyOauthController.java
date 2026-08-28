package com.exampleco.crm.controller.auth;

import com.exampleco.crm.config.auth.LegacyServiceAuthProperties;
import com.exampleco.crm.service.auth.LegacyServiceTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// The server side of the exact grant_type=refresh_token / /oauth/access_token contract legacy-app's
// own JwtClient (pkg-core's api.JwtClient/JwtClientFilter) already speaks as a client against
// legacy-app's 4 storefront hosts — see LegacyApiClient's own use of that same contract in the
// opposite direction. Publicly reachable (SecurityConfig permits it) since legacy-app has no session
// established yet when it asks for a token.
@RestController
public class LegacyOauthController {

    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    private static final String GRANT_TYPE_PARAM = "grant_type";
    private static final String REFRESH_TOKEN_PARAM = "refresh_token";

    private final LegacyServiceAuthProperties properties;
    private final LegacyServiceTokenService tokenService;

    public LegacyOauthController(LegacyServiceAuthProperties properties, LegacyServiceTokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
    }

    // Matches legacy-app's own AccessTokenResponse shape (LegacyApiClient's own record of the same
    // name) — just the one field either side actually reads.
    public record AccessTokenResponse(String access_token) {
    }

    // Reads the credentials out of the form BODY, which is where legacy-app actually puts them:
    // JwtClient declares produces = APPLICATION_FORM_URLENCODED, so Micronaut encodes
    // grant_type/refresh_token as the request body, not the query string.
    //
    // This used to bind them with @RequestParam. That works on Spring MVC, where @RequestParam
    // covers both the query string and a form body — but NOT on WebFlux, which this app runs on,
    // where it reads the query string only. The consumes= below made the endpoint accept the
    // content type, so it looked correct; the two parameters simply never bound, and every real
    // request from legacy-app got 400 before the token was ever compared. Nothing in this app could
    // observe that, because its own outbound direction (LegacyApiClient -> legacy-app) posts to Grails,
    // which is servlet-stack MVC and reads form bodies happily.
    //
    // Query parameters are still honoured as a fallback so this cannot silently break again if a
    // future client encodes them the other way.
    @PostMapping(value = "/oauth/access_token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AccessTokenResponse>> accessToken(ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            MultiValueMap<String, String> query = exchange.getRequest().getQueryParams();
            String grantType = firstPresent(form.getFirst(GRANT_TYPE_PARAM), query.getFirst(GRANT_TYPE_PARAM));
            String refreshToken = firstPresent(form.getFirst(REFRESH_TOKEN_PARAM), query.getFirst(REFRESH_TOKEN_PARAM));
            if (!GRANT_TYPE_REFRESH_TOKEN.equals(grantType)
                    || !constantTimeEquals(refreshToken, properties.refreshToken())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).<AccessTokenResponse>build();
            }
            return ResponseEntity.ok(new AccessTokenResponse(tokenService.issue()));
        });
    }

    private static String firstPresent(String fromBody, String fromQuery) {
        return fromBody != null ? fromBody : fromQuery;
    }

    // MessageDigest.isEqual is a constant-time comparison — a plain String.equals here would let
    // an attacker infer the secret one byte at a time from response-time differences (mirrors
    // LegacyServiceAuthenticationFilter's own reasoning for the same check).
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
