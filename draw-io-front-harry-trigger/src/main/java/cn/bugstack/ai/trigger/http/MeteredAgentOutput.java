package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record MeteredAgentOutput(
        @JsonUnwrapped
        AgentOutputEventVO output,
        Integer remaining
) {
}
