package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

@Component
public class MeteredAgentChatFacade {

    private final IAiQuotaService quotaService;
    private final IChatService chatService;

    public MeteredAgentChatFacade(
            IAiQuotaService quotaService,
            IChatService chatService
    ) {
        this.quotaService = quotaService;
        this.chatService = chatService;
    }

    public MeteredChatResult chat(
            String userId,
            String requestId,
            String agentId,
            String sessionId,
            String message
    ) {
        quotaService.reserve(
                userId,
                requestId,
                agentId,
                "chat"
        );

        boolean committed = false;

        try {
            String actualSessionId = sessionId;

            if (actualSessionId == null || actualSessionId.isBlank()) {
                actualSessionId = chatService.createSession(agentId, userId);
            }

            AgentChatResultVO result =
                    chatService.handleMessage(
                            agentId,
                            userId,
                            actualSessionId,
                            message
                    );

            if (result == null || result.getContent() == null || result.getContent().isBlank()) {
                throw new AppException(
                        ResponseCode.AI_EMPTY_RESPONSE.getCode(),
                        ResponseCode.AI_EMPTY_RESPONSE.getInfo()
                );
            }

            QuotaSnapshotEntity snapshot =
                    quotaService.commit(
                            userId,
                            requestId
                    );


            committed = true;

            return new MeteredChatResult(
                    result,
                    snapshot.remaining()
            );
        } finally {
            if (!committed) {
                quotaService.release(
                        userId,
                        requestId
                );
            }
        }
    }
}
