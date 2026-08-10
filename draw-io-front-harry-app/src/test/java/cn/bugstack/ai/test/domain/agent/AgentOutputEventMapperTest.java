package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.OutputModeEnum;
import cn.bugstack.ai.domain.agent.service.chat.AgentOutputEventMapper;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;

public class AgentOutputEventMapperTest {

    private final AgentOutputEventMapper mapper = new AgentOutputEventMapper();

    @Test
    public void shouldPreserveContentWhenOutputModeIsAllEvents() {
        AiAgentRegisterVO register = AiAgentRegisterVO.builder()
                .outputMode(OutputModeEnum.ALL_EVENTS)
                .build();
        Event event = textEvent("OnlyAgent", "answer");

        Optional<AgentOutputEventVO> output = mapper.map(event, register);

        Assert.assertTrue(output.isPresent());
        Assert.assertEquals(AgentOutputEventVO.Type.EVENT, output.get().getType());
        Assert.assertEquals("answer", output.get().getContent());
    }

    @Test
    public void shouldHideFunctionCallFromResponseAgent() {
        AiAgentRegisterVO register = AiAgentRegisterVO.builder()
                .responseAgentName("SynthesisAgent")
                .outputMode(OutputModeEnum.FINAL_WITH_TRACE)
                .build();
        Event event = Event.builder()
                .author("SynthesisAgent")
                .content(Content.fromParts(
                        Part.fromFunctionCall("search", Map.of("query", "energy"))))
                .build();

        Optional<AgentOutputEventVO> output = mapper.map(event, register);

        Assert.assertFalse(output.isPresent());
    }

    @Test
    public void shouldMapChildFinalResponseToTrace() {
        AiAgentRegisterVO register = AiAgentRegisterVO.builder()
                .responseAgentName("SynthesisAgent")
                .outputMode(OutputModeEnum.FINAL_WITH_TRACE)
                .build();

        Optional<AgentOutputEventVO> output = mapper.map(
                textEvent("EVResearcher", "research result"), register);

        Assert.assertTrue(output.isPresent());
        Assert.assertEquals(AgentOutputEventVO.Type.TRACE, output.get().getType());
        Assert.assertTrue(output.get().isCompleted());
    }

    @Test
    public void shouldHideChildResponseInFinalOnlyMode() {
        AiAgentRegisterVO register = AiAgentRegisterVO.builder()
                .responseAgentName("SynthesisAgent")
                .outputMode(OutputModeEnum.FINAL_ONLY)
                .build();

        Optional<AgentOutputEventVO> output = mapper.map(
                textEvent("EVResearcher", "research result"), register);

        Assert.assertFalse(output.isPresent());
    }

    private Event textEvent(String author, String text) {
        return Event.builder()
                .author(author)
                .content(Content.fromParts(Part.fromText(text)))
                .build();
    }
}
