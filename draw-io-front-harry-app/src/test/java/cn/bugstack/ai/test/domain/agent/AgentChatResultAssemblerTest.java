package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import cn.bugstack.ai.domain.agent.service.chat.AgentChatResultAssembler;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class AgentChatResultAssemblerTest {

    private final AgentChatResultAssembler assembler = new AgentChatResultAssembler();

    @Test
    public void shouldSeparateTraceFromFinalContent() {
        List<AgentOutputEventVO> events = List.of(
                event(AgentOutputEventVO.Type.TRACE, "ResearcherA", "trace-a", true),
                event(AgentOutputEventVO.Type.TRACE, "ResearcherB", "trace-b", true),
                event(AgentOutputEventVO.Type.FINAL, "SynthesisAgent", "final-report", true)
        );

        AgentChatResultVO result = assembler.assemble(events);

        Assert.assertEquals("final-report", result.getContent());
        Assert.assertEquals(2, result.getTraces().size());
        Assert.assertEquals("ResearcherA", result.getTraces().get(0).getAgentName());
    }

    @Test
    public void shouldIgnoreIncompleteFinalEventInSynchronousResult() {
        List<AgentOutputEventVO> events = List.of(
                event(AgentOutputEventVO.Type.FINAL, "SynthesisAgent", "partial", false),
                event(AgentOutputEventVO.Type.FINAL, "SynthesisAgent", "complete", true)
        );

        AgentChatResultVO result = assembler.assemble(events);

        Assert.assertEquals("complete", result.getContent());
    }

    @Test
    public void shouldPreserveLegacyAllEventsContent() {
        List<AgentOutputEventVO> events = List.of(
                event(AgentOutputEventVO.Type.EVENT, "OnlyAgent", "tool-event", false),
                event(AgentOutputEventVO.Type.EVENT, "OnlyAgent", "answer", true)
        );

        AgentChatResultVO result = assembler.assemble(events);

        Assert.assertEquals("tool-event\nanswer", result.getContent());
        Assert.assertTrue(result.getTraces().isEmpty());
    }

    private AgentOutputEventVO event(
            AgentOutputEventVO.Type type,
            String agentName,
            String content,
            boolean completed) {
        return AgentOutputEventVO.builder()
                .type(type)
                .agentName(agentName)
                .content(content)
                .completed(completed)
                .build();
    }
}
