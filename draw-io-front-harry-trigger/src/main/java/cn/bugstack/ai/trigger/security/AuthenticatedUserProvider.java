package cn.bugstack.ai.trigger.security;

import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthenticatedUserProvider {

    public String requireUserId() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new InsufficientAuthenticationException(
                    "Supabase user is not authenticated");
        }

        String subject = jwtAuthentication.getToken().getSubject();

        try {
            return UUID.fromString(subject).toString();
        } catch (IllegalArgumentException exception) {
            throw new InsufficientAuthenticationException(
                    "Supabase subject is not a valid UUID",
                    exception);
        }
    }
}
