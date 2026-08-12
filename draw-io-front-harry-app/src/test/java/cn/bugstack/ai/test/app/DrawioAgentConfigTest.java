package cn.bugstack.ai.test.app;

import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class DrawioAgentConfigTest {

    @Test
    public void drawioAgentDoesNotExposeDemoUppercaseTool() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "agent-draw-io",
                new ClassPathResource("agent/agent-draw-io.yml")
        );

        String propertyPrefix = "ai.agent.config.tables.agentDrawIo.module.chat-model.tool-mcp-list[";
        boolean exposesDemoProvider = sources.stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .map(source -> (EnumerablePropertySource<?>) source)
                .flatMap(source -> Arrays.stream(source.getPropertyNames())
                        .filter(name -> name.startsWith(propertyPrefix) && name.endsWith("].local.name"))
                        .map(source::getProperty))
                .anyMatch("myToolCallbackProvider"::equals);

        assertFalse("Draw.io agent must not expose the demo uppercase tool", exposesDemoProvider);
    }

    @Test
    public void drawioAgentStreamsCompletedStageResults() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "agent-draw-io",
                new ClassPathResource("agent/agent-draw-io.yml")
        );

        String property = "ai.agent.config.tables.agentDrawIo.module.runner.output-mode";
        Object outputMode = sources.stream()
                .map(source -> source.getProperty(property))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);

        assertEquals(
                "Draw.io agent must stream completed child Agent results",
                "FINAL_WITH_TRACE",
                outputMode
        );
    }

    @Test
    public void drawioAgentShortCircuitsIncompleteRequestsBeforeDrawing() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "agent-draw-io",
                new ClassPathResource("agent/agent-draw-io.yml")
        );
        String workflows = "ai.agent.config.tables.agentDrawIo.module.agent-workflows";

        assertEquals("conditional", property(sources, workflows + "[2].type"));
        assertEquals("ReadyDrawingWorkflow", property(sources, workflows + "[2].name"));
        assertEquals("request_analysis", property(sources, workflows + "[2].condition-state-key"));
        assertEquals("status", property(sources, workflows + "[2].condition-json-field"));
        assertEquals("READY", property(sources, workflows + "[2].condition-expected-value"));

        assertEquals("FinalResponseAgent",
                property(sources, "ai.agent.config.tables.agentDrawIo.module.agents[6].name"));
        assertEquals("sequential", property(sources, workflows + "[3].type"));
        assertEquals("DrawioPipeline", property(sources, workflows + "[3].name"));
        assertEquals("RequestAnalystAgent", property(sources, workflows + "[3].sub-agents[0]"));
        assertEquals("ReadyDrawingWorkflow", property(sources, workflows + "[3].sub-agents[1]"));
        assertEquals("FinalResponseAgent", property(sources, workflows + "[3].sub-agents[2]"));
        assertNull(property(sources, workflows + "[3].sub-agents[3]"));
    }

    private Object property(List<PropertySource<?>> sources, String propertyName) {
        return sources.stream()
                .map(source -> source.getProperty(propertyName))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
