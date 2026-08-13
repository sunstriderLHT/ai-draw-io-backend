package cn.bugstack.ai.test.security;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProductionDeploymentConfigTest {

    @Test
    public void shouldKeepBackendPrivateOnBothProductionNetworks() throws Exception {
        Path composePath = Path.of(
                "..", "docs", "dev-ops", "docker-compose-production.yml"
        ).normalize();
        String compose = Files.readString(composePath, StandardCharsets.UTF_8);

        assertTrue(compose.contains("container_name: drawio-backend"));
        assertTrue(compose.contains("expose:"));
        assertTrue(compose.contains("- \"8091\""));
        assertFalse(compose.contains("ports:"));
        assertFalse(compose.contains("8091:8091"));
        assertTrue(compose.contains("name: deploy_app"));
        assertTrue(compose.contains("name: software_my-network"));
        assertTrue(compose.contains("env_file:"));
        assertTrue(compose.contains("${DRAWIO_BACKEND_ENV_FILE:-.env}"));
        assertFalse(compose.matches("(?s).*AI_MODEL_API_KEY:\\s*[^$<\\s].*"));
        assertFalse(compose.matches("(?s).*SPRING_DATASOURCE_PASSWORD:\\s*[^$<\\s].*"));
    }
}
