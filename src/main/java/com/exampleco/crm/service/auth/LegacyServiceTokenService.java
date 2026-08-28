package com.exampleco.crm.service.auth;

import com.exampleco.crm.config.auth.LegacyServiceAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

// The server-side mirror of LegacyApiClient's client-side refresh-token exchange: issues the JWT
// legacy-app redeems at LegacyOauthController's POST /oauth/access_token, and verifies the JWT legacy-app
// then presents as its Bearer credential (LegacyServiceAuthenticationFilter). Stateless —
// signature + exp claim is the whole check, no server-side session/revocation store, mirroring
// legacy-app's own JwtClientFilter which likewise never persists anything about tokens it issues.
@Component
public class LegacyServiceTokenService {

    private static final Logger log = LoggerFactory.getLogger(LegacyServiceTokenService.class);

    // Matches LegacyApiClient's own TOKEN_TTL for the opposite direction.
    private static final Duration TOKEN_TTL = Duration.ofSeconds(3600);
    private static final String SUBJECT = "legacy-app";

    private final LegacyServiceAuthProperties properties;

    public LegacyServiceTokenService(LegacyServiceAuthProperties properties) {
        this.properties = properties;
    }

    public String issue() {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(SUBJECT)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(TOKEN_TTL)))
                    .build();
            JWSSigner signer = new MACSigner(signingKeyBytes());
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign legacy-app service JWT", ex);
        }
    }

    // Empty on any failure (malformed, bad signature, expired, wrong subject) — the filter
    // treats all of these identically, same as a non-matching static secret did before.
    public Optional<String> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSVerifier verifier = new MACVerifier(signingKeyBytes());
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }
            if (!SUBJECT.equals(claims.getSubject())) {
                return Optional.empty();
            }
            return Optional.of(claims.getSubject());
        } catch (ParseException | JOSEException ex) {
            log.debug("Rejecting invalid legacy-app service JWT: {}", ex.toString());
            return Optional.empty();
        }
    }

    private byte[] signingKeyBytes() {
        return properties.jwtSigningKey().getBytes(StandardCharsets.UTF_8);
    }
}
