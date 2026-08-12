package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.domain.agent.model.valobj.AgentChatResultVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.quota.model.entity.QuotaSnapshotEntity;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.domain.agent.model.valobj.AgentOutputEventVO;
import io.reactivex.rxjava3.core.Flowable;

import java.util.concurrent.atomic.AtomicBoolean;

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

    public Flowable<MeteredAgentOutput> chatStream(
            String userId,
            String requestId,
            String agentId,
            String sessionId,
            String message
    ) {
        return Flowable.defer(() -> {
            quotaService.reserve(
                    userId,
                    requestId,
                    agentId,
                    "chat_stream"
            );
            // 是否已经产生有效输出并成功提交消费
            AtomicBoolean committed =
                    new AtomicBoolean(false);
            // 是否已经执行过释放，防止错误、取消、完成等多个终止回调重复释放
            AtomicBoolean released =
                    new AtomicBoolean(false);

            return chatService.handleMessageStream(agentId, userId, sessionId, message)
                    .map(output -> {
                        Integer remaining = null;

                        if (isEffective(output) && committed.compareAndSet(false, true)) {
                            try {
                                remaining = quotaService.commit(userId, requestId).remaining();
                            } catch (RuntimeException exception) {
                                committed.set(false);
                                throw exception;
                            }
                        }

                        return new MeteredAgentOutput(output, remaining);
                    })
                    .doOnError(error ->
                            releaseBeforeOutput(userId, requestId, committed, released)
                    ).doOnCancel(() ->
                            releaseBeforeOutput(userId, requestId, committed, released)
                    ).doOnComplete(() ->
                            releaseBeforeOutput(userId, requestId, committed, released)
                    );
        });
    }

    // 判断事件是否包含有效的非空模型内容。
    private boolean isEffective(AgentOutputEventVO output) {
        return output != null
                && output.getContent() != null
                && !output.getContent().isBlank();
    }

    private void releaseBeforeOutput(
            String userId,
            String requestId,
            AtomicBoolean committed,
            AtomicBoolean released
    ) {
        if (!committed.get() && released.compareAndSet(false, true)) {
            quotaService.release(userId, requestId);
        }
    }
}
