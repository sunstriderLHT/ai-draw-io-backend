package cn.bugstack.ai.test.security;

import org.junit.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.MySQLContainer;

import static org.junit.Assert.assertNotNull;

public class DependencySmokeTest {

    @Test
    public void shouldLoadSecurityAndDatabaseTestTypes() {
        assertNotNull(JwtDecoder.class);
        assertNotNull(MySQLContainer.class);
    }
}
