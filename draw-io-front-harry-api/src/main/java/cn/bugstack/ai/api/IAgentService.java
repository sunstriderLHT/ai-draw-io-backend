package cn.bugstack.ai.api;


import cn.bugstack.ai.api.dto.AiAgentConfigResponseDTO;
import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ChatResponseDTO;
import cn.bugstack.ai.api.dto.CreateSessionRequestDTO;
import cn.bugstack.ai.api.dto.CreateSessionResponseDTO;
import cn.bugstack.ai.api.dto.QuotaResponseDTO;
import cn.bugstack.ai.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体服务接口
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();


    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(String requestId, ChatRequestDTO chatRequestDTO);

    ResponseBodyEmitter chatStream(String requestId, ChatRequestDTO chatRequestDTO);

    Response<QuotaResponseDTO> queryQuota();


}
