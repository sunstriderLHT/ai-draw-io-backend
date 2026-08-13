package cn.bugstack.ai.test.security;

import cn.bugstack.ai.trigger.security.SupabaseJwtValidator;
import org.junit.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SupabaseJwtValidatorTest {

    private static final String EXPECTED_ISSUER =
            "https://test-project.invalid/auth/v1";

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private final SupabaseJwtValidator validator =
            new SupabaseJwtValidator(EXPECTED_ISSUER);

    @Test
    public void shouldAcceptValidTokenFromExpectedIssuer() {
        Instant now = Instant.now();

        Jwt jwt = createJwt(
                EXPECTED_ISSUER,
                now.minusSeconds(10),
                now.plusSeconds(300));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
    }

    @Test
    public void shouldRejectTokenFromAnotherIssuer() {
        Instant now = Instant.now();

        Jwt jwt = createJwt(
                "https://another-project.invalid/auth/v1",
                now.minusSeconds(10),
                now.plusSeconds(300));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }

    @Test
    public void shouldRejectExpiredToken() {
        Instant now = Instant.now();

        Jwt jwt = createJwt(
                EXPECTED_ISSUER,
                now.minusSeconds(600),
                now.minusSeconds(300));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }

    private Jwt createJwt(
            String issuer,
            Instant issuedAt,
            Instant expiresAt) {

        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject(USER_ID)
                .audience(List.of("authenticated"))
                .claim("role", "authenticated")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
    }
}
