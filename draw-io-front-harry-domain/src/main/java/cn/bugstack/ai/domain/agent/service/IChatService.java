package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    AgentChatResultVO handleMessage(String agentId, String userId, String message);
    AgentChatResultVO handleMessage(String agentId, String userId,String sessionId, String message);

    Flowable<AgentOutputEventVO> handleMessageStream(String agentId, String userId, String sessionId, String message);

    AgentChatResultVO handleMessage(ChatCommandEntity chatCommandEntity);
}
