package cn.bugstack.ai.trigger.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(SupabaseAuthProperties.class)
public class SupabaseSecurityConfig {
    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            SupabaseAuthProperties properties,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/**")
                        .access((authentication, context) -> {
                            boolean allowed = authentication.get().isAuthenticated()
                                    && properties.isAllowed(authentication.get().getName());
                            return new AuthorizationDecision(allowed);
                        })
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    @Bean
    public JwtDecoder supabaseJwtDecoder(
            SupabaseAuthProperties properties) {

        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(
                                properties.jwksUri().toString())
                        .jwsAlgorithms(algorithms -> {
                            algorithms.add(SignatureAlgorithm.RS256);
                            algorithms.add(SignatureAlgorithm.ES256);
                        })
                        .build();

        decoder.setJwtValidator(
                new SupabaseJwtValidator(
                        properties.issuer().toString()));

        return decoder;
    }
}
