package cn.bugstack.ai.trigger.http;

import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AgentStreamHeartbeatScheduler {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 3L;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ScheduledExecutorService executor;

    public AgentStreamHeartbeatScheduler() {
        this(Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(
                    task,
                    "agent-sse-heartbeat-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }));
    }

    AgentStreamHeartbeatScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public ScheduledFuture<?> schedule(Runnable heartbeat) {
        return executor.scheduleAtFixedRate(
                heartbeat,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
