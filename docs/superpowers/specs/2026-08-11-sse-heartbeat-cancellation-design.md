# SSE Heartbeat Cancellation Design

## Goal

Detect a browser-aborted Agent stream within approximately three seconds, release the workflow subscription promptly, and log the exact stream termination reason.

## Problem

The browser already aborts the `/api/v1/chat_stream` fetch. However, a servlet container may not detect a disconnected client until the server writes again. A long-running LLM call can therefore finish and consume tokens before the final SSE write fails.

The observed request ran for 161.7 seconds and consumed 32,422 tokens after the browser had stopped waiting. The backend detected the closed connection only when it attempted to send the final Draw.io XML.

## Scope

This is generic SSE transport lifecycle infrastructure. It does not change Agent workflow semantics, Draw.io behavior, the `/chat_stream` request body, or business SSE events.

The implementation adds:

- a shared backend heartbeat scheduler;
- one cancellable heartbeat registration per active SSE request;
- one lifecycle object that owns the emitter, Agent subscription, heartbeat task, and terminal state;
- reason-specific lifecycle logging.

It does not add a cancellation endpoint or task registry in this iteration. The purpose is to make disconnect detection prompt and then observe whether disposing the upstream Agent flow cancels the underlying model request.

## Heartbeat Protocol

- Send one SSE comment every three seconds.
- Use `SseEmitter.event().comment("heartbeat")` so the frame carries no business event or data payload.
- Do not log successful heartbeats.
- The current frontend SSE parser ignores comment-only frames, so no frontend protocol change is required.
- A heartbeat write failure is treated as a disconnected client.

## Shared Scheduler

Use one application-scoped `ScheduledExecutorService`, not one thread per request. Each active stream registers a `ScheduledFuture` with a three-second initial delay and a three-second period.

The scheduler uses named daemon threads so it does not block application shutdown. The owning Spring component shuts the scheduler down during bean destruction.

## Stream Lifecycle

Introduce a generic `AgentStreamLifecycle` object for each `/chat_stream` request. It owns:

- the request metadata used for logs: `agentId`, `userId`, and `sessionId`;
- the `SseEmitter`;
- the `AgentStreamSubscription`;
- the scheduled heartbeat future;
- an atomic terminal state.

The lifecycle exposes bounded operations:

- register the Agent `Disposable`;
- register the heartbeat future;
- send a business SSE event;
- send a heartbeat comment;
- terminate normally;
- terminate because the client disconnected;
- terminate because of timeout;
- terminate because the Agent flow failed.

All terminal methods use compare-and-set so only the first terminal reason performs cleanup and logging. Cleanup always cancels the heartbeat future and disposes the Agent subscription. A subscription or heartbeat registered after termination is cancelled immediately, preserving the existing race-safe behavior.

## Termination Semantics

- **Normal completion:** cancel heartbeat, dispose the subscription, complete the emitter, and log one `INFO` message.
- **Client disconnect:** cancel heartbeat, dispose the subscription, avoid retrying a write to the closed response, and log one `INFO` message with request metadata.
- **Timeout:** cancel heartbeat, dispose the subscription, and log one `WARN` message.
- **Agent failure:** cancel heartbeat, dispose the subscription, complete the emitter with the original error, and log one `ERROR` message.
- **Controller setup failure:** terminate through the same Agent-failure path.

Output-write and heartbeat-write failures caused by `IOException`, `AsyncRequestNotUsableException`, or another response-write exception are classified as client disconnects. The original exception is logged at debug detail or attached to the single disconnect log without producing repeated error noise.

## Concurrency

Heartbeat and Agent-output sends can occur on different threads. The lifecycle serializes emitter writes through a private lock and checks terminal state before writing. Termination uses the same ownership boundary so a heartbeat cannot continue after normal completion and a late Agent event cannot be sent after disconnect.

No blocking wait is added to the request thread. Heartbeat work consists only of a small comment write and termination cleanup on failure.

## Logging

Log one terminal line per stream:

- `流式任务正常完成 agentId:{} userId:{} sessionId:{}` at `INFO`;
- `流式任务被客户端中断 agentId:{} userId:{} sessionId:{}` at `INFO`;
- `流式任务执行超时 agentId:{} userId:{} sessionId:{}` at `WARN`;
- `流式任务异常结束 agentId:{} userId:{} sessionId:{}` at `ERROR` with the cause.

Successful heartbeat ticks are intentionally silent.

## Verification Strategy

Unit tests use a controllable emitter, fake subscription, and fake scheduled future to verify:

- a heartbeat comment is emitted through the lifecycle;
- a heartbeat write failure terminates as client disconnect and disposes the Agent subscription;
- normal completion cancels heartbeat and disposes the subscription;
- timeout and Agent failure select the correct single terminal state;
- repeated or racing termination calls clean up only once;
- a subscription or heartbeat registered after termination is immediately cancelled;
- output and heartbeat writes cannot continue after termination.

Controller tests verify that `/chat_stream` creates a lifecycle, schedules heartbeat at three-second intervals, and routes Agent output, completion, error, emitter timeout, and emitter error to the correct lifecycle methods.

After deployment, reproduce a long Draw.io generation, stop it from the frontend, and verify:

1. the backend logs client interruption within approximately three seconds;
2. no later Agent in the workflow starts;
3. whether Spring AI still logs completion for the already-running model request.

If the model request still completes, heartbeat detection and workflow cancellation are working, but the model client does not propagate disposal to its HTTP call. That would justify a separate follow-up design for model-level cancellation rather than expanding this transport change.
