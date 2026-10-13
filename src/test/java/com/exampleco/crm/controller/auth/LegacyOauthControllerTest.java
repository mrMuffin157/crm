package com.exampleco.crm.controller.auth;

import com.exampleco.crm.config.auth.LegacyServiceAuthProperties;
import com.exampleco.crm.service.auth.LegacyServiceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * legacy-app's JwtClient declares {@code produces = APPLICATION_FORM_URLENCODED}, so Micronaut
 * sends grant_type/refresh_token in the request BODY. This endpoint originally bound them
 * with {@code @RequestParam}, which reads the query string only on WebFlux (it covers form
 * bodies on Spring MVC, which is where that idiom comes from). The parameters never bound and
 * every real request from legacy-app was rejected with 400 — before the token was ever compared,
 * so the 400-vs-401 distinction is the whole tell.
 *
 * The body case below is exactly what legacy-app puts on the wire, and is the one that regressed.
 */
class LegacyOauthControllerTest {

    private static final String SECRET = "s3cret-refresh-token";

    private final LegacyServiceTokenService tokenService = mock(LegacyServiceTokenService.class);
    private final LegacyOauthController controller =
            new LegacyOauthController(new LegacyServiceAuthProperties(SECRET, "signing-key"), tokenService);

    private ResponseEntity<LegacyOauthController.AccessTokenResponse> post(String body, String query) {
        MockServerHttpRequest.BodyBuilder builder =
                MockServerHttpRequest.post("/oauth/access_token" + (query == null ? "" : "?" + query))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        ServerWebExchange exchange = MockServerWebExchange.from(builder.body(body == null ? "" : body));
        return controller.accessToken(exchange).block();
    }

    @Test
    void issuesATokenForCredentialsInTheFormBody() {
        when(tokenService.issue()).thenReturn("a.jwt.value");

        ResponseEntity<LegacyOauthController.AccessTokenResponse> response =
                post("grant_type=refresh_token&refresh_token=" + SECRET, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().access_token()).isEqualTo("a.jwt.value");
    }

    @Test
    void issuesATokenForCredentialsInTheQueryString() {
        when(tokenService.issue()).thenReturn("a.jwt.value");

        ResponseEntity<LegacyOauthController.AccessTokenResponse> response =
                post(null, "grant_type=refresh_token&refresh_token=" + SECRET);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsAWrongRefreshToken() {
        ResponseEntity<LegacyOauthController.AccessTokenResponse> response =
                post("grant_type=refresh_token&refresh_token=not-the-secret", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAnUnsupportedGrantType() {
        ResponseEntity<LegacyOauthController.AccessTokenResponse> response =
                post("grant_type=client_credentials&refresh_token=" + SECRET, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsARequestCarryingNoCredentialsAtAll() {
        ResponseEntity<LegacyOauthController.AccessTokenResponse> response = post(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
