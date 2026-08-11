package cn.bugstack.ai.trigger.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;


public class SupabaseClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final String AUTHENTICATED = "authenticated";

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!jwt.getAudience().contains(AUTHENTICATED)) {
            return failure("Token audience must include authenticated");
        }

        String role = jwt.getClaimAsString("role");
        if (!AUTHENTICATED.equals(role)) {
            return failure("Token role must be authenticated");
        }

        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult failure(String description) {
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                description,
                null);

        return OAuth2TokenValidatorResult.failure(error);
    }
}
