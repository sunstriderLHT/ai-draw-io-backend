package cn.bugstack.ai.test.security;

import cn.bugstack.ai.trigger.security.SupabaseClaimsValidator;
import org.junit.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SupabaseClaimsValidatorTest {

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private final SupabaseClaimsValidator validator =
            new SupabaseClaimsValidator();

    @Test
    public void shouldAcceptAuthenticatedUserToken() {
        Jwt jwt = createJwt(List.of("authenticated"), "authenticated");

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
    }

    @Test
    public void shouldRejectTokenForAnotherAudience() {
        Jwt jwt = createJwt(List.of("another-service"), "authenticated");

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }

    @Test
    public void shouldRejectTokenWithAnotherRole() {
        Jwt jwt = createJwt(List.of("authenticated"), "anon");

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }

    private Jwt createJwt(List<String> audience, String role) {
        Instant now = Instant.parse("2026-08-11T04:00:00Z");

        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(USER_ID)
                .audience(audience)
                .claim("role", role)
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
