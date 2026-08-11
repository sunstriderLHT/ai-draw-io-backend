package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.service.armory.node.workflow.ConditionalAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ConditionalAgentTest {

    @Test
    public void matchingJsonStateRunsChildren() {
        CountingAgent child = new CountingAgent("DrawingChild");
        ConditionalAgent agent = conditionalAgent(child);

        List<Event> events = agent.runAsync(context(agent, "{\"status\":\"READY\"}"))
                .toList()
                .blockingGet();

        assertEquals(1, child.getInvocationCount());
        assertEquals(1, events.size());
        assertEquals("DrawingChild", events.get(0).author());
    }

    @Test
    public void nonMatchingJsonStateSkipsChildren() {
        CountingAgent child = new CountingAgent("DrawingChild");
        ConditionalAgent agent = conditionalAgent(child);

        List<Event> events = agent.runAsync(context(agent, "{\"status\":\"NEED_MORE_INFO\"}"))
                .toList()
                .blockingGet();

        assertEquals(0, child.getInvocationCount());
        assertEquals(0, events.size());
    }

    @Test
    public void malformedJsonStateSkipsChildren() {
        CountingAgent child = new CountingAgent("DrawingChild");
        ConditionalAgent agent = conditionalAgent(child);

        List<Event> events = agent.runAsync(context(agent, "not-json"))
                .toList()
                .blockingGet();

        assertEquals(0, child.getInvocationCount());
        assertEquals(0, events.size());
    }

    @Test
    public void missingStateSkipsChildren() {
        CountingAgent child = new CountingAgent("DrawingChild");
        ConditionalAgent agent = conditionalAgent(child);

        List<Event> events = agent.runAsync(context(agent, null))
                .toList()
                .blockingGet();

        assertEquals(0, child.getInvocationCount());
        assertEquals(0, events.size());
    }

    private ConditionalAgent conditionalAgent(BaseAgent child) {
        return new ConditionalAgent(
                "ReadyDrawingWorkflow",
                "Run drawing Agents only for complete requests",
                Collections.singletonList(child),
                "request_analysis",
                "status",
                "READY"
        );
    }

    private InvocationContext context(BaseAgent agent, String requestAnalysis) {
        Map<String, Object> state = new HashMap<>();
        if (requestAnalysis != null) {
            state.put("request_analysis", requestAnalysis);
        }

        Session session = Session.builder("session-id")
                .appName("drawio-test")
                .userId("test-user")
                .state(state)
                .build();

        return InvocationContext.builder()
                .invocationId("invocation-id")
                .agent(agent)
                .session(session)
                .sessionService(new InMemorySessionService())
                .build();
    }

    private static class CountingAgent extends BaseAgent {

        private int invocationCount;

        private CountingAgent(String name) {
            super(name, "Test child", Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            invocationCount++;
            return Flowable.just(Event.builder()
                    .author(name())
                    .invocationId(invocationContext.invocationId())
                    .build());
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return runAsyncImpl(invocationContext);
        }

        private int getInvocationCount() {
            return invocationCount;
        }
    }
}
