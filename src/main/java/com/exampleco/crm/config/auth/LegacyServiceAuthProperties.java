package com.exampleco.crm.config.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds app.legacy-service.* — the inbound mirror of LegacyApiProperties.refreshToken() in
// config.salesorder. legacy-app presents refreshToken() to LegacyOauthController's
// POST /oauth/access_token (the same grant_type=refresh_token contract legacy-app's own
// /oauth/access_token speaks) to redeem a short-lived JWT signed with jwtSigningKey(), then uses
// that JWT as its Bearer credential against this app's ticket M2M API — verified by
// LegacyServiceTokenService/LegacyServiceAuthenticationFilter. Both are long-lived secrets with no
// default, same treatment as DB_USER/DB_PASSWORD.
@ConfigurationProperties(prefix = "app.legacy-service")
public record LegacyServiceAuthProperties(String refreshToken, String jwtSigningKey) {
}
