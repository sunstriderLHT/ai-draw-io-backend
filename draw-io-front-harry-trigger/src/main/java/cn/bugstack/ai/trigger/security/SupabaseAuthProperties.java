package cn.bugstack.ai.trigger.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@ConfigurationProperties(prefix = "supabase.auth")
public record SupabaseAuthProperties(
        URI issuer,
        URI jwksUri,
        Set<UUID> allowedUserIds) {

    public SupabaseAuthProperties {
        issuer = Objects.requireNonNull(
                issuer,
                "supabase.auth.issuer must be configured");

        jwksUri = Objects.requireNonNull(
                jwksUri,
                "supabase.auth.jwks-uri must be configured");

        allowedUserIds = allowedUserIds == null
                ? Set.of()
                : Set.copyOf(allowedUserIds);
    }

    public boolean isAllowed(String subject) {
        try {
            return allowedUserIds.contains(UUID.fromString(subject));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}