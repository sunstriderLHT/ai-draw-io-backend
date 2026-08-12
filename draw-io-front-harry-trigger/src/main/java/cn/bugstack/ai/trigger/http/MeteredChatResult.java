package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;

public record MeteredChatResult(
        AgentChatResultVO result,
        int remaining
) {
}
