package cn.bugstack.ai.test.app;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.utils.InstructionUtils;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DrawioFinalResponseInstructionTest {

    @Test
    public void finalInstructionAcceptsMissingDrawingStateAfterShortCircuit() throws IOException {
        String instruction = finalResponseInstruction();
        InvocationContext context = contextWithRequestAnalysisOnly();

        String injected;
        try {
            injected = InstructionUtils.injectSessionState(context, instruction).blockingGet();
        } catch (RuntimeException exception) {
            fail("FinalResponseAgent instruction must allow missing short-circuited state: " + exception.getMessage());
            return;
        }

        assertTrue(injected.contains("NEED_MORE_INFO"));
        assertFalse(injected.contains("{research_context"));
        assertFalse(injected.contains("{diagram_plan"));
        assertFalse(injected.contains("{drawio_xml"));
    }

    private String finalResponseInstruction() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "agent-draw-io",
                new ClassPathResource("agent/agent-draw-io.yml")
        );
        String property = "ai.agent.config.tables.agentDrawIo.module.agents[6].instruction";
        return sources.stream()
                .map(source -> source.getProperty(property))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("FinalResponseAgent instruction is missing"));
    }

    private InvocationContext contextWithRequestAnalysisOnly() {
        Map<String, Object> state = new HashMap<>();
        state.put("request_analysis", "{\"status\":\"NEED_MORE_INFO\",\"clarificationQuestion\":\"请选择支付流程类型\"}");
        Session session = Session.builder("session-id")
                .appName("drawio-test")
                .userId("test-user")
                .state(state)
                .build();
        BaseAgent agent = new NoOpAgent();
        return InvocationContext.builder()
                .invocationId("invocation-id")
                .agent(agent)
                .session(session)
                .sessionService(new InMemorySessionService())
                .build();
    }

    private static class NoOpAgent extends BaseAgent {

        private NoOpAgent() {
            super("FinalResponseAgent", "Test Agent", Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return Flowable.empty();
        }
    }
}
