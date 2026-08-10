package cn.bugstack.ai.domain.agent.service.chat;

import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AgentChatResultAssembler {

    public AgentChatResultVO assemble(List<AgentOutputEventVO> events) {
        String content = events.stream()
                .filter(event -> event.getType() == AgentOutputEventVO.Type.EVENT
                        || (event.getType() == AgentOutputEventVO.Type.FINAL
                        && event.isCompleted()))
                .map(AgentOutputEventVO::getContent)
                .collect(Collectors.joining("\n"));

        List<AgentOutputEventVO> traces = events.stream()
                .filter(event -> event.getType() == AgentOutputEventVO.Type.TRACE)
                .toList();

        return AgentChatResultVO.builder()
                .content(content)
                .traces(traces)
                .build();
    }
}
