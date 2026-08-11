package cn.bugstack.ai.domain.agent.service.armory.node.workflow;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
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

public class ConditionalAgentNodeTest {

    @Test
    public void configuredConditionControlsTheBuiltAgent() {
        AiAgentConfigTableVO.Module.AgentWorkflow workflow = readyWorkflow();
        ConditionalAgentNode node = new ConditionalAgentNode();
        EmittingAgent child = new EmittingAgent("DrawingChild");

        ConditionalAgent agent = node.buildAgent(workflow, Collections.singletonList(child));

        assertEquals("ReadyDrawingWorkflow", agent.name());
        assertEquals(1, agent.runAsync(context(agent, "{\"status\":\"READY\"}"))
                .toList().blockingGet().size());
        assertEquals(0, agent.runAsync(context(agent, "{\"status\":\"NEED_MORE_INFO\"}"))
                .toList().blockingGet().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankConditionStateKeyIsRejected() {
        AiAgentConfigTableVO.Module.AgentWorkflow workflow = readyWorkflow();
        workflow.setConditionStateKey(" ");

        new ConditionalAgentNode().buildAgent(
                workflow,
                Collections.singletonList(new EmittingAgent("DrawingChild"))
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyResolvedChildrenAreRejected() {
        new ConditionalAgentNode().buildAgent(readyWorkflow(), Collections.emptyList());
    }

    private AiAgentConfigTableVO.Module.AgentWorkflow readyWorkflow() {
        AiAgentConfigTableVO.Module.AgentWorkflow workflow = new AiAgentConfigTableVO.Module.AgentWorkflow();
        workflow.setName("ReadyDrawingWorkflow");
        workflow.setDescription("Run drawing Agents only for complete requests");
        workflow.setConditionStateKey("request_analysis");
        workflow.setConditionJsonField("status");
        workflow.setConditionExpectedValue("READY");
        return workflow;
    }

    private InvocationContext context(BaseAgent agent, String requestAnalysis) {
        Map<String, Object> state = new HashMap<>();
        state.put("request_analysis", requestAnalysis);
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

    private static class EmittingAgent extends BaseAgent {

        private EmittingAgent(String name) {
            super(name, "Test child", Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
            return Flowable.just(Event.builder()
                    .author(name())
                    .invocationId(invocationContext.invocationId())
                    .build());
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
            return runAsyncImpl(invocationContext);
        }
    }
}
