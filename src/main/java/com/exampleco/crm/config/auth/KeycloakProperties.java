package com.exampleco.crm.config.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Binds the app.keycloak.* keys from application.properties — kept as one typed record rather
// than scattered @Value fields since SecurityConfig and AuthController both need the same values
// (enabled flag, and the pieces needed to build the Keycloak end-session URL for two-step logout).
@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakProperties(
        boolean enabled,
        String baseUrl,
        @DefaultValue("exampleco") String realm,
        @DefaultValue("crm") String clientId,
        String postLogoutRedirectUri) {
}
