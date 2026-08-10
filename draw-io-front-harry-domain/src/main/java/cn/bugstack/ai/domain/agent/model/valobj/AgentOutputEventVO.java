package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentOutputEventVO {

    public enum Type {
        EVENT,
        TRACE,
        FINAL
    }

    private Type type;

    private String agentName;

    private String content;

    /**
     * 是否为该 Agent 的最终内容事件。
     */
    private boolean completed;
}
