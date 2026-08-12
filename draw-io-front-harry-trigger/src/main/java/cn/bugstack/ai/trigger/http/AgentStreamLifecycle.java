package cn.bugstack.ai.trigger.http;

import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class AgentStreamLifecycle {

    public enum TerminationReason {
        ACTIVE,
        COMPLETED,
        CLIENT_DISCONNECTED,
        TIMED_OUT,
        FAILED
    }

    private final Object sendLock = new Object();
    private final SseEmitter emitter;
    private final String agentId;
    private final String userId;
    private final String sessionId;
    private final AgentStreamSubscription subscription = new AgentStreamSubscription();
    private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
    private final AtomicReference<TerminationReason> termination =
            new AtomicReference<>(TerminationReason.ACTIVE);

    public AgentStreamLifecycle(
            SseEmitter emitter,
            String agentId,
            String userId,
            String sessionId) {
        this.emitter = emitter;
        this.agentId = agentId;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    public void registerSubscription(Disposable value) {
        subscription.set(value);
    }

    public void registerHeartbeat(ScheduledFuture<?> value) {
        if (termination.get() != TerminationReason.ACTIVE) {
            value.cancel(false);
            return;
        }
        if (!heartbeat.compareAndSet(null, value)) {
            value.cancel(false);
            return;
        }
        if (termination.get() != TerminationReason.ACTIVE
                && heartbeat.compareAndSet(value, null)) {
            value.cancel(false);
        }
    }

    public boolean send(SseEmitter.SseEventBuilder event) {
        synchronized (sendLock) {
            if (termination.get() != TerminationReason.ACTIVE) return false;
            try {
                emitter.send(event);
                return true;
            } catch (Exception cause) {
                terminate(TerminationReason.CLIENT_DISCONNECTED, cause);
                return false;
            }
        }
    }

    public void heartbeat() {
        send(SseEmitter.event().comment("heartbeat"));
    }

    public void completeNormally() {
        terminate(TerminationReason.COMPLETED, null);
    }

    public void clientCompletedConnection() {
        terminate(TerminationReason.CLIENT_DISCONNECTED, null);
    }

    public void clientDisconnected(Throwable cause) {
        terminate(TerminationReason.CLIENT_DISCONNECTED, cause);
    }

    public void timeout() {
        terminate(TerminationReason.TIMED_OUT, null);
    }

    public void fail(Throwable cause) {
        terminate(TerminationReason.FAILED, cause);
    }

    public TerminationReason terminationReason() {
        return termination.get();
    }

    private void terminate(TerminationReason reason, Throwable cause) {
        synchronized (sendLock) {
            if (!termination.compareAndSet(TerminationReason.ACTIVE, reason)) return;

            ScheduledFuture<?> scheduledHeartbeat = heartbeat.getAndSet(null);
            if (scheduledHeartbeat != null) scheduledHeartbeat.cancel(false);
            // TODO: Runner disposal stops pending workflow stages, but Google ADK does not
            // propagate it to already-started LLM/MCP requests. Add task-scoped cancellation.
            subscription.dispose();

            switch (reason) {
                case COMPLETED -> {
                    log.info("流式任务正常完成 agentId:{} userId:{} sessionId:{}",
                            agentId, userId, sessionId);
                    emitter.complete();
                }
                case CLIENT_DISCONNECTED -> log.info(
                        "流式任务被客户端中断 agentId:{} userId:{} sessionId:{}",
                        agentId, userId, sessionId);
                case TIMED_OUT -> log.warn(
                        "流式任务执行超时 agentId:{} userId:{} sessionId:{}",
                        agentId, userId, sessionId);
                case FAILED -> {
                    log.error("流式任务异常结束 agentId:{} userId:{} sessionId:{}",
                            agentId, userId, sessionId, cause);
                    emitter.completeWithError(cause);
                }
                default -> throw new IllegalStateException("Unsupported termination reason: " + reason);
            }
        }
    }
}
