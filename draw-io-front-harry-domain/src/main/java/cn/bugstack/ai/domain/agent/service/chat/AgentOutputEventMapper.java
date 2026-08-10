package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.OutputModeEnum;
import com.google.adk.events.Event;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AgentOutputEventMapper {

    public Optional<AgentOutputEventVO> map(Event event, AiAgentRegisterVO register) {
        // 获取agent输出content
        String content = event.stringifyContent();
        if (StringUtils.isBlank(content)) {
            return Optional.empty();
        }
        // 获取最终输出节点agent和输出模式
        String responseAgent = register.getResponseAgentName();
        OutputModeEnum outputMode = register.getOutputMode();

        // 兼容没有配置新输出策略的既有 Agent。
        if (outputMode == null || OutputModeEnum.ALL_EVENTS == outputMode) {
            return Optional.of(
                    AgentOutputEventVO.builder()
                            .type(AgentOutputEventVO.Type.EVENT)
                            .agentName(event.author())
                            .content(content)
                            .completed(event.finalResponse())
                            .build()
            );
        }

        // 当前是否是最终输出节点
        boolean fromResponseAgent = StringUtils.equals(responseAgent, event.author());

        // 最终回答 Agent 的内容始终作为 FINAL
        if (fromResponseAgent) {
            if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(
                    AgentOutputEventVO.builder()
                            .type(AgentOutputEventVO.Type.FINAL)
                            .agentName(event.author())
                            .content(content)
                            .completed(event.finalResponse())
                            .build()
            );
        }

        // 只把子 Agent 的最终内容作为执行轨迹，
        // 避免工具调用、模型中间事件全部暴露给前端
        if (OutputModeEnum.FINAL_WITH_TRACE == outputMode
                && event.finalResponse()) {
            return Optional.of(
                    AgentOutputEventVO.builder()
                            .type(AgentOutputEventVO.Type.TRACE)
                            .agentName(event.author())
                            .content(content)
                            .completed(true)
                            .build()
            );
        }

        // FINAL_ONLY 下，子 Agent 事件全部隐藏
        return Optional.empty();
    }
}
