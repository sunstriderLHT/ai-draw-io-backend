package cn.bugstack.ai.trigger.http;

import org.junit.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;

public class AgentStreamHeartbeatSchedulerTest {

    @Test
    public void schedulesHeartbeatEveryThreeSeconds() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        Runnable heartbeat = mock(Runnable.class);
        doReturn(future).when(executor).scheduleAtFixedRate(
                same(heartbeat), eq(3L), eq(3L), eq(TimeUnit.SECONDS));

        AgentStreamHeartbeatScheduler scheduler = new AgentStreamHeartbeatScheduler(executor);

        assertSame(future, scheduler.schedule(heartbeat));
        verify(executor).scheduleAtFixedRate(
                same(heartbeat), eq(3L), eq(3L), eq(TimeUnit.SECONDS));
    }

    @Test
    public void shutsDownExecutorWhenApplicationStops() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        AgentStreamHeartbeatScheduler scheduler = new AgentStreamHeartbeatScheduler(executor);

        scheduler.shutdown();

        verify(executor).shutdownNow();
    }
}
