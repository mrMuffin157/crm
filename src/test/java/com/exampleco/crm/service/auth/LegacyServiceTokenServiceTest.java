package com.exampleco.crm.service.auth;

import com.exampleco.crm.config.auth.LegacyServiceAuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyServiceTokenServiceTest {

    private static final String SIGNING_KEY = "a".repeat(32);

    private final LegacyServiceTokenService service =
            new LegacyServiceTokenService(new LegacyServiceAuthProperties("unused-refresh-token", SIGNING_KEY));

    @Test
    void issuedTokenVerifiesSuccessfully() {
        String token = service.issue();

        Optional<String> subject = service.verify(token);

        assertThat(subject).contains("legacy-app");
    }

    @Test
    void rejectsTokenSignedWithADifferentKey() throws JOSEException {
        String token = signWith("z".repeat(32), "legacy-app", Instant.now().plusSeconds(3600));

        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() throws JOSEException {
        String token = signWith(SIGNING_KEY, "legacy-app", Instant.now().minusSeconds(1));

        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void rejectsTokenWithWrongSubject() throws JOSEException {
        String token = signWith(SIGNING_KEY, "someone-else", Instant.now().plusSeconds(3600));

        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void rejectsMalformedToken() {
        assertThat(service.verify("not-a-jwt")).isEmpty();
    }

    private static String signWith(String key, String subject, Instant expiration) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiration))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
