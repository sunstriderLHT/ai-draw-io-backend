package cn.bugstack.ai.trigger.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

public class SupabaseJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final OAuth2TokenValidator<Jwt> delegate;

    public SupabaseJwtValidator(String issuer) {
        this.delegate = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new SupabaseClaimsValidator());
    }
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return delegate.validate(jwt);
    }
}
