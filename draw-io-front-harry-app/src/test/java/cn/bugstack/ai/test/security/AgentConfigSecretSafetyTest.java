package cn.bugstack.ai.test.security;

import org.junit.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentConfigSecretSafetyTest {

    private static final Pattern API_KEY_LINE =
            Pattern.compile(
                    "^[ \\t]*api-key[ \\t]*:[ \\t]*(.+?)[ \\t]*$",
                    Pattern.MULTILINE
            );

    private static final Pattern ENV_REFERENCE =
            Pattern.compile(
                    "[\"']?\\$\\{[A-Z][A-Z0-9_]*(?::[^}]*)?}[\"']?"
            );

    @Test
    public void shouldNotPackageLiteralAgentApiKeys() throws Exception {
        Resource[] resources =
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:agent/*.yml");

        for (Resource resource : resources) {
            String yaml = new String(
                    resource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            Matcher matcher = API_KEY_LINE.matcher(yaml);

            while (matcher.find()) {
                assertTrue(
                        resource.getFilename()
                                + " contains a literal api-key",
                        ENV_REFERENCE.matcher(
                                matcher.group(1)
                        ).matches()
                );
            }
        }
    }

    @Test
    public void shouldNotLogCompleteAgentConfiguration() throws Exception {
        Path source = locateAutoConfigSource();
        String javaSource = Files.readString(
                source,
                StandardCharsets.UTF_8
        );

        assertFalse(
                "完整智能体配置可能包含模型密钥，禁止序列化到日志",
                javaSource.contains(
                        "JSON.toJSONString(aiAgentAutoConfigProperties"
                )
        );
    }

    private Path locateAutoConfigSource() {
        String relative =
                "src/main/java/cn/bugstack/ai/config/"
                        + "AiAgentAutoConfig.java";

        List<Path> candidates = List.of(
                Path.of(relative),
                Path.of("draw-io-front-harry-app")
                        .resolve(relative)
        );

        return candidates.stream()
                .map(Path::toAbsolutePath)
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "找不到 AiAgentAutoConfig.java"
                        )
                );
    }
}
