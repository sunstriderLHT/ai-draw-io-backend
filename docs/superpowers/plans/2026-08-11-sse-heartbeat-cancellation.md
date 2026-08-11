# SSE Heartbeat Cancellation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect an aborted browser SSE connection within about three seconds, release the active Agent workflow subscription, and log one accurate termination reason.

**Architecture:** Add a request-scoped `AgentStreamLifecycle` that serializes emitter writes and atomically owns terminal cleanup, plus an application-scoped `AgentStreamHeartbeatScheduler` that schedules one lightweight comment heartbeat per active stream. `AgentServiceController` delegates all emitter callbacks and Agent subscriber callbacks to the lifecycle. No frontend, request-body, business-event, or workflow changes are required.

**Tech Stack:** Java 17, Spring Boot 3.4.3 MVC, `SseEmitter`, RxJava 3, JUnit 4, Mockito, Maven multi-module build.

## Global Constraints

- Heartbeat period and initial delay are exactly three seconds.
- Heartbeats use `SseEmitter.event().comment("heartbeat")` and never become business events.
- Successful heartbeats produce no log entry.
- Only the first terminal cause performs cleanup or emits a terminal log.
- Cleanup always cancels the heartbeat future and disposes the Agent subscription, including late registration races.
- Client disconnect is `INFO`, normal completion is `INFO`, timeout is `WARN`, and Agent failure is `ERROR`.
- Do not add a cancellation endpoint, task registry, frontend change, or Agent workflow behavior.
- Preserve the existing uncommitted 20-minute emitter-timeout change and all unrelated backend worktree changes.

---

### Task 1: Atomic stream lifecycle

**Files:**
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentStreamLifecycle.java`
- Create: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/trigger/http/AgentStreamLifecycleTest.java`
- Reuse: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentStreamSubscription.java`

**Interfaces:**
- Constructor: `AgentStreamLifecycle(SseEmitter emitter, String agentId, String userId, String sessionId)`.
- Registration: `registerSubscription(Disposable value)` and `registerHeartbeat(ScheduledFuture<?> value)`.
- Activity: `send(SseEmitter.SseEventBuilder event)` and `heartbeat()`.
- Termination: `completeNormally()`, `clientDisconnected(Throwable cause)`, `timeout()`, and `fail(Throwable cause)`.
- Observation for tests: `TerminationReason terminationReason()` with enum values `ACTIVE`, `COMPLETED`, `CLIENT_DISCONNECTED`, `TIMED_OUT`, and `FAILED`.

- [ ] **Step 1: Write lifecycle tests first**

Create `AgentStreamLifecycleTest` in package `cn.bugstack.ai.trigger.http`. Use a small emitter double that counts sends/completion and can throw `IOException`, plus Mockito `Disposable` and `ScheduledFuture<?>` instances.

```java
@Test
public void shouldDisposeSubscriptionAndCancelHeartbeatWhenHeartbeatDetectsDisconnect() throws Exception {
    RecordingEmitter emitter = new RecordingEmitter();
    emitter.failWrites = true;
    Disposable disposable = mock(Disposable.class);
    ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    AgentStreamLifecycle lifecycle = new AgentStreamLifecycle(
            emitter, "100004", "admin", "session-1");
    lifecycle.registerSubscription(disposable);
    lifecycle.registerHeartbeat(heartbeat);

    lifecycle.heartbeat();

    Assert.assertEquals(
            AgentStreamLifecycle.TerminationReason.CLIENT_DISCONNECTED,
            lifecycle.terminationReason());
    verify(disposable).dispose();
    verify(heartbeat).cancel(false);
    Assert.assertEquals(1, emitter.sendAttempts);
}
```

Add independent tests with literal expectations for:

```java
// completeNormally(): reason COMPLETED, heartbeat cancelled once,
// subscription disposed once, emitter.complete() called once.

// timeout(): reason TIMED_OUT and resources cancelled once.

// fail(original): reason FAILED, resources cancelled once,
// emitter.completeWithError receives the exact original Throwable.

// clientDisconnected() followed by completeNormally()/timeout()/fail():
// first reason wins and cleanup methods remain called once.

// registerSubscription/registerHeartbeat after termination:
// each late resource is cancelled immediately.

// send() and heartbeat() after termination:
// emitter send count does not increase.
```

The mutation caught by these tests is any missing disposal, leaked heartbeat, duplicate terminal action, overwritten termination reason, or late write.

- [ ] **Step 2: Run lifecycle tests and verify RED**

Run:

```powershell
mvn.cmd -pl draw-io-front-harry-app -am -Dtest=AgentStreamLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `AgentStreamLifecycle` does not exist.

- [ ] **Step 3: Implement the lifecycle**

Create `AgentStreamLifecycle` with this structure:

```java
@Slf4j
public final class AgentStreamLifecycle {

    public enum TerminationReason {
        ACTIVE, COMPLETED, CLIENT_DISCONNECTED, TIMED_OUT, FAILED
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

    // constructor and public methods
}
```

Registration follows the existing race-safe subscription pattern:

```java
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
```

Serialize writes and classify write failures as disconnects:

```java
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
```

Implement one private `terminate(TerminationReason reason, Throwable cause)` using `compareAndSet(ACTIVE, reason)`. On success it cancels `heartbeat.getAndSet(null)`, disposes `subscription`, performs the reason-specific emitter completion, and writes exactly one reason-specific log containing `agentId`, `userId`, and `sessionId`. `CLIENT_DISCONNECTED` must not call `completeWithError` against the already unusable response.

Expose `terminationReason()` for deterministic tests. Keep the class generic to SSE transport; it must not mention Draw.io or any Agent name.

- [ ] **Step 4: Run lifecycle tests and verify GREEN**

Run the same Maven command from Step 2.

Expected: every `AgentStreamLifecycleTest` case passes.

---

### Task 2: Shared heartbeat scheduler

**Files:**
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentStreamHeartbeatScheduler.java`
- Create: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/trigger/http/AgentStreamHeartbeatSchedulerTest.java`

**Interfaces:**
- Spring constructor: `AgentStreamHeartbeatScheduler()` creates the shared daemon scheduler.
- Test constructor: package-private `AgentStreamHeartbeatScheduler(ScheduledExecutorService executor)`.
- Scheduling: `ScheduledFuture<?> schedule(Runnable heartbeat)`.
- Shutdown: `shutdown()` annotated with `@PreDestroy`.

- [ ] **Step 1: Write the scheduler test**

```java
@Test
public void shouldScheduleHeartbeatEveryThreeSeconds() {
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    Runnable heartbeat = mock(Runnable.class);
    when(executor.scheduleAtFixedRate(
            same(heartbeat), eq(3L), eq(3L), eq(TimeUnit.SECONDS)))
            .thenReturn(future);
    AgentStreamHeartbeatScheduler scheduler =
            new AgentStreamHeartbeatScheduler(executor);

    Assert.assertSame(future, scheduler.schedule(heartbeat));
    verify(executor).scheduleAtFixedRate(
            same(heartbeat), eq(3L), eq(3L), eq(TimeUnit.SECONDS));
}
```

Add a shutdown test verifying `shutdownNow()` is called exactly once.

- [ ] **Step 2: Run scheduler tests and verify RED**

```powershell
mvn.cmd -pl draw-io-front-harry-app -am -Dtest=AgentStreamHeartbeatSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the scheduler class does not exist.

- [ ] **Step 3: Implement the scheduler**

Create a `@Component` with constants `HEARTBEAT_DELAY_SECONDS = 3L` and `HEARTBEAT_PERIOD_SECONDS = 3L`. The default constructor creates one single-thread scheduled executor using a thread factory that names daemon threads `agent-sse-heartbeat-N`.

```java
public ScheduledFuture<?> schedule(Runnable heartbeat) {
    return executor.scheduleAtFixedRate(
            heartbeat,
            HEARTBEAT_DELAY_SECONDS,
            HEARTBEAT_PERIOD_SECONDS,
            TimeUnit.SECONDS);
}

@PreDestroy
public void shutdown() {
    executor.shutdownNow();
}
```

Use `jakarta.annotation.PreDestroy`, matching Spring Boot 3 lifecycle APIs.

- [ ] **Step 4: Run scheduler tests and verify GREEN**

Run the Step 2 command and expect all scheduler tests to pass.

---

### Task 3: Controller integration

**Files:**
- Modify: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java`
- Create: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/trigger/http/AgentServiceControllerHeartbeatTest.java`

**Interfaces:**
- `AgentServiceController` consumes `AgentStreamHeartbeatScheduler.schedule(Runnable)`.
- `/chat_stream` creates one `AgentStreamLifecycle`, registers callbacks, registers heartbeat, then registers the Agent subscription.
- Existing request and response types remain unchanged.

- [ ] **Step 1: Write a focused controller wiring test**

Use Mockito to inject a mocked `IChatService` and `AgentStreamHeartbeatScheduler` into a controller instance. Capture the scheduled heartbeat runnable and return a mocked `ScheduledFuture<?>`. Return `Flowable.never()` from `handleMessageStream`.

```java
@Test
public void shouldScheduleHeartbeatForEveryChatStream() {
    when(chatService.handleMessageStream("100004", "admin", "session-1", "draw"))
            .thenReturn(Flowable.never());
    when(heartbeatScheduler.schedule(any(Runnable.class)))
            .thenReturn(heartbeatFuture);

    controller.chatStream(request("100004", "admin", "session-1", "draw"));

    verify(heartbeatScheduler).schedule(any(Runnable.class));
    verify(chatService).handleMessageStream("100004", "admin", "session-1", "draw");
}
```

Add a second test using `Flowable.just(output)` or `Flowable.error(original)` to verify the controller delegates completion/error without leaving the scheduled future active. Assert observable cleanup on the captured future rather than implementation-private calls.

- [ ] **Step 2: Run the controller wiring test and verify RED**

```powershell
mvn.cmd -pl draw-io-front-harry-app -am -Dtest=AgentServiceControllerHeartbeatTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the controller has no heartbeat scheduler integration.

- [ ] **Step 3: Refactor `/chat_stream` to the lifecycle**

Inject the scheduler with the project's existing field-injection style:

```java
@Resource
private AgentStreamHeartbeatScheduler heartbeatScheduler;
```

Replace the direct `AgentStreamSubscription` callbacks with:

```java
SseEmitter emitter = new SseEmitter(20 * 60 * 1000L);
AgentStreamLifecycle lifecycle = new AgentStreamLifecycle(
        emitter,
        requestDTO.getAgentId(),
        requestDTO.getUserId(),
        requestDTO.getSessionId());
emitter.onCompletion(lifecycle::clientCompletedConnection);
emitter.onTimeout(lifecycle::timeout);
emitter.onError(lifecycle::clientDisconnected);
lifecycle.registerHeartbeat(heartbeatScheduler.schedule(lifecycle::heartbeat));
```

Add `clientCompletedConnection()` to the lifecycle as an alias that classifies an emitter completion as client disconnect only while still `ACTIVE`; a prior `completeNormally()` has already set `COMPLETED`, so its completion callback becomes a no-op.

Subscribe with lifecycle delegation:

```java
Disposable disposable = chatService.handleMessageStream(...).subscribe(
        output -> lifecycle.send(SseEmitter.event()
                .name(output.getType().name().toLowerCase(Locale.ROOT))
                .data(output, MediaType.APPLICATION_JSON)),
        lifecycle::fail,
        lifecycle::completeNormally);
lifecycle.registerSubscription(disposable);
```

Route controller setup exceptions through `lifecycle.fail(error)`. Remove the old repeated `try/catch`, direct emitter completion, and generic `流式对话发送失败` stack trace; the lifecycle now owns classification and single cleanup.

- [ ] **Step 4: Run all focused tests**

```powershell
mvn.cmd -pl draw-io-front-harry-app -am -Dtest=AgentStreamLifecycleTest,AgentStreamHeartbeatSchedulerTest,AgentServiceControllerHeartbeatTest,AgentStreamSubscriptionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all heartbeat, lifecycle, controller, and subscription tests pass.

- [ ] **Step 5: Run backend verification**

```powershell
mvn.cmd -pl draw-io-front-harry-app -am -DskipTests package
mvn.cmd -pl draw-io-front-harry-app -Dtest=AgentStreamLifecycleTest,AgentStreamHeartbeatSchedulerTest,AgentServiceControllerHeartbeatTest,AgentStreamSubscriptionTest test
git diff --check
```

Expected: compile/package succeeds, focused tests pass, and diff check reports no whitespace errors. If the repository's unrelated existing tests fail, report the exact failures without modifying unrelated Agent workflow or configuration files.

- [ ] **Step 6: Review scope and leave mixed files unstaged**

```powershell
git diff -- draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java
git status --short
```

Verify the existing 20-minute timeout remains. Because the controller and subscription files already contain user-owned uncommitted changes, leave implementation unstaged unless an exact heartbeat-only staged diff can be proven with `git diff --cached`.
