package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentChatResultVO {

    private String content;

    private List<AgentOutputEventVO> traces;
}
