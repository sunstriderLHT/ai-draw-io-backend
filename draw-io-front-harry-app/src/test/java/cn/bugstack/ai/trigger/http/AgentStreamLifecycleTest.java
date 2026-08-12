package cn.bugstack.ai.trigger.http;

import io.reactivex.rxjava3.disposables.Disposable;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class AgentStreamLifecycleTest {

    @Test
    public void shouldDisposeResourcesWhenHeartbeatDetectsDisconnect() {
        RecordingEmitter emitter = new RecordingEmitter();
        emitter.failWrites = true;
        Disposable disposable = mock(Disposable.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        AgentStreamLifecycle lifecycle = lifecycle(emitter);
        lifecycle.registerSubscription(disposable);
        lifecycle.registerHeartbeat(heartbeat);

        lifecycle.heartbeat();

        Assert.assertEquals(
                AgentStreamLifecycle.TerminationReason.CLIENT_DISCONNECTED,
                lifecycle.terminationReason());
        verify(disposable).dispose();
        verify(heartbeat).cancel(false);
        Assert.assertEquals(1, emitter.sendAttempts);
        Assert.assertEquals(0, emitter.errorCompletions);
    }

    @Test
    public void shouldCompleteNormallyAndReleaseResources() {
        RecordingEmitter emitter = new RecordingEmitter();
        Disposable disposable = mock(Disposable.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        AgentStreamLifecycle lifecycle = lifecycle(emitter);
        lifecycle.registerSubscription(disposable);
        lifecycle.registerHeartbeat(heartbeat);

        lifecycle.completeNormally();

        Assert.assertEquals(
                AgentStreamLifecycle.TerminationReason.COMPLETED,
                lifecycle.terminationReason());
        verify(disposable).dispose();
        verify(heartbeat).cancel(false);
        Assert.assertEquals(1, emitter.completions);
    }

    @Test
    public void shouldCompleteWithTheOriginalAgentFailure() {
        RecordingEmitter emitter = new RecordingEmitter();
        Disposable disposable = mock(Disposable.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        AgentStreamLifecycle lifecycle = lifecycle(emitter);
        lifecycle.registerSubscription(disposable);
        lifecycle.registerHeartbeat(heartbeat);
        IllegalStateException failure = new IllegalStateException("agent failed");

        lifecycle.fail(failure);

        Assert.assertEquals(
                AgentStreamLifecycle.TerminationReason.FAILED,
                lifecycle.terminationReason());
        Assert.assertSame(failure, emitter.completionError);
        Assert.assertEquals(1, emitter.errorCompletions);
        verify(disposable).dispose();
        verify(heartbeat).cancel(false);
    }

    @Test
    public void shouldKeepTheFirstTerminationReasonAndCleanUpOnce() {
        RecordingEmitter emitter = new RecordingEmitter();
        Disposable disposable = mock(Disposable.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        AgentStreamLifecycle lifecycle = lifecycle(emitter);
        lifecycle.registerSubscription(disposable);
        lifecycle.registerHeartbeat(heartbeat);

        lifecycle.timeout();
        lifecycle.completeNormally();
        lifecycle.clientDisconnected(new IOException("late disconnect"));
        lifecycle.fail(new IllegalStateException("late failure"));

        Assert.assertEquals(
                AgentStreamLifecycle.TerminationReason.TIMED_OUT,
                lifecycle.terminationReason());
        verify(disposable, times(1)).dispose();
        verify(heartbeat, times(1)).cancel(false);
        Assert.assertEquals(0, emitter.completions);
        Assert.assertEquals(0, emitter.errorCompletions);
    }

    @Test
    public void shouldCancelResourcesRegisteredAfterTermination() {
        RecordingEmitter emitter = new RecordingEmitter();
        AgentStreamLifecycle lifecycle = lifecycle(emitter);
        lifecycle.clientDisconnected(new IOException("closed"));
        Disposable lateDisposable = mock(Disposable.class);
        ScheduledFuture<?> lateHeartbeat = mock(ScheduledFuture.class);

        lifecycle.registerSubscription(lateDisposable);
        lifecycle.registerHeartbeat(lateHeartbeat);

        verify(lateDisposable).dispose();
        verify(lateHeartbeat).cancel(false);
    }

    @Test
    public void shouldRejectLateBusinessAndHeartbeatWrites() {
        RecordingEmitter emitter = new RecordingEmitter();
        AgentStreamLifecycle lifecycle = lifecycle(emitter);
        lifecycle.clientDisconnected(new IOException("closed"));

        Assert.assertFalse(lifecycle.send(SseEmitter.event().name("trace").data("late")));
        lifecycle.heartbeat();

        Assert.assertEquals(0, emitter.sendAttempts);
    }

    private AgentStreamLifecycle lifecycle(RecordingEmitter emitter) {
        return new AgentStreamLifecycle(emitter, "100004", "admin", "session-1");
    }

    private static final class RecordingEmitter extends SseEmitter {
        private boolean failWrites;
        private int sendAttempts;
        private int completions;
        private int errorCompletions;
        private Throwable completionError;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempts++;
            if (failWrites) throw new IOException("client disconnected");
        }

        @Override
        public void complete() {
            completions++;
        }

        @Override
        public void completeWithError(Throwable ex) {
            errorCompletions++;
            completionError = ex;
        }
    }
}
