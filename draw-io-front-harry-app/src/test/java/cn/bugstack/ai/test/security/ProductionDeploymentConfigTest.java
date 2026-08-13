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
        Path runbookPath = Path.of(
                "..", "docs", "dev-ops", "backend-auth-quota-runbook.md"
        ).normalize();
        String compose = Files.readString(composePath, StandardCharsets.UTF_8);
        String runbook = Files.readString(runbookPath, StandardCharsets.UTF_8);

        assertTrue(compose.contains("container_name: drawio-backend"));
        assertTrue(compose.contains("expose:"));
        assertTrue(compose.contains("- \"8091\""));
        assertFalse(compose.contains("ports:"));
        assertFalse(compose.contains("8091:8091"));
        assertTrue(compose.contains("name: deploy_app"));
        assertTrue(compose.contains("name: software_my-network"));
        assertTrue(compose.contains("env_file:"));
        assertTrue(compose.contains("${DRAWIO_BACKEND_ENV_FILE:-.env}"));
        assertTrue(compose.contains("./log:/data/log"));
        assertFalse(compose.contains("./log:/app/data/log"));
        assertFalse(compose.matches("(?s).*AI_MODEL_API_KEY:\\s*[^$<\\s].*"));
        assertFalse(compose.matches("(?s).*SPRING_DATASOURCE_PASSWORD:\\s*[^$<\\s].*"));

        assertTrue(runbook.contains("STATUS=401"));
        assertTrue(runbook.contains("STATUS=200"));
        assertTrue(runbook.contains("AUTH_TOKEN_INVALID"));
        assertTrue(runbook.contains("\"code\":\"0000\""));
        assertTrue(runbook.contains("--config -"));
        assertTrue(runbook.contains("if ! sudo docker tag"));
        assertTrue(runbook.contains("if ! sudo docker compose"));
        assertTrue(runbook.contains("回滚镜像不存在，停止回滚"));
        assertTrue(runbook.contains("if ! ss_output=\"$(sudo ss -lntp)\""));
        assertTrue(runbook.contains("无法读取宿主机监听端口"));
        assertTrue(runbook.contains("running_image_id=\"$(sudo docker inspect"));
        assertTrue(runbook.contains("--format '{{.Image}}' drawio-backend"));
        assertTrue(runbook.contains("--format '{{.State.Running}}' drawio-backend"));
        assertTrue(runbook.contains("if [ \"$running_state\" != \"true\" ]"));
        assertTrue(runbook.contains("\"$running_image_id\""));
        assertTrue(runbook.contains("deploy/README.md"));
        assertTrue(runbook.contains("确认旧版本与当前 Flyway schema 向前兼容"));
        assertTrue(runbook.contains("ROLLBACK_SCHEMA_APPROVED"));
        assertFalse(runbook.contains("-H \"Authorization: Bearer $ACCESS_TOKEN\""));
        assertFalse(runbook.contains("if sudo ss -lntp | grep"));
        assertFalse(runbook.contains("sudo nginx -s reload"));
        assertFalse(runbook.contains("docker compose -f deploy/docker-compose.yml restart nginx"));

        String mutableLatestBackup = String.join("\n",
                "    drawio-backend:latest \\",
                "    drawio-backend:before-production-deploy; then"
        );
        assertFalse(runbook.contains(mutableLatestBackup));

        int approval = runbook.indexOf("ROLLBACK_SCHEMA_APPROVED");
        int rollbackRetag = runbook.lastIndexOf("drawio-backend:latest; then");
        assertTrue(approval >= 0 && rollbackRetag > approval);
    }
}
