package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.OutputModeEnum;
import cn.bugstack.ai.domain.agent.service.armory.node.workflow.AgentOutputPolicyValidator;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Set;

public class AgentOutputPolicyValidatorTest {

    private final AgentOutputPolicyValidator validator = new AgentOutputPolicyValidator();

    @Test
    public void shouldAllowAllEventsWithoutResponseAgent() {
        OutputModeEnum outputMode = validator.validate(
                runner("ALL_EVENTS", null),
                Set.of("OnlyAgent"));

        Assert.assertEquals(OutputModeEnum.ALL_EVENTS, outputMode);
    }

    @Test
    public void shouldDefaultBlankModeToAllEventsAndNormalizeConfig() {
        AiAgentConfigTableVO.Module.Runner runner = runner(" ", null);

        OutputModeEnum outputMode = validator.validate(runner, Set.of("OnlyAgent"));

        Assert.assertEquals(OutputModeEnum.ALL_EVENTS, outputMode);
        Assert.assertEquals("ALL_EVENTS", runner.getOutputMode());
    }

    @Test(expected = AppException.class)
    public void shouldRejectUnknownOutputMode() {
        validator.validate(runner("UNKNOWN", null), Set.of("OnlyAgent"));
    }

    @Test(expected = AppException.class)
    public void shouldRequireResponseAgentForFilteredModes() {
        validator.validate(runner("FINAL_WITH_TRACE", null), Set.of("SynthesisAgent"));
    }

    @Test(expected = AppException.class)
    public void shouldRejectUnknownResponseAgent() {
        validator.validate(
                runner("FINAL_ONLY", "MissingAgent"),
                Set.of("SynthesisAgent"));
    }

    @Test
    public void shouldAllowKnownResponseAgent() {
        AiAgentConfigTableVO.Module.Runner runner =
                runner(" final_with_trace ", "SynthesisAgent");

        OutputModeEnum outputMode = validator.validate(
                runner,
                Set.of("SynthesisAgent"));

        Assert.assertEquals(OutputModeEnum.FINAL_WITH_TRACE, outputMode);
        Assert.assertEquals("FINAL_WITH_TRACE", runner.getOutputMode());
    }

    private AiAgentConfigTableVO.Module.Runner runner(
            String outputMode,
            String responseAgentName) {
        AiAgentConfigTableVO.Module.Runner runner =
                new AiAgentConfigTableVO.Module.Runner();
        runner.setOutputMode(outputMode);
        runner.setResponseAgentName(responseAgentName);
        return runner;
    }
}
