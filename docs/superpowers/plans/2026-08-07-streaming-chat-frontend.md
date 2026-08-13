# Streaming Chat Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static frontend's synchronous `/chat` request with cancellable POST SSE streaming that updates one final-answer bubble and a collapsed per-agent trace area.

**Architecture:** A DOM-independent `sse-client.js` owns incremental SSE framing and streamed-text merging. `index.js` owns fetch lifecycle, `AbortController`, and DOM updates; `index.html` owns loading order and presentation styles. The existing backend SSE contract remains unchanged.

**Tech Stack:** Browser Fetch API, ReadableStream, AbortController, vanilla JavaScript, HTML/CSS, Node 24 `node:test`

## Global Constraints

- Do not add npm dependencies, package.json, or a frontend build system.
- Keep POST `/chat_stream` and its JSON request body.
- Render `trace` separately from the final answer and keep traces collapsed by default.
- Treat trace data as child-agent output, not hidden model chain-of-thought.
- Preserve the existing Markdown escaping path.
- Do not stage, commit, or overwrite unrelated working-tree changes.

---

### Task 1: Incremental SSE parser and streamed-text merger

**Files:**
- Create: `docs/dev-ops/nginx/html/js/sse-client.js`
- Create: `docs/dev-ops/nginx/html/js/sse-client.test.js`

**Interfaces:**
- Consumes: arbitrary decoded text chunks and an `onEvent({ event, data })` callback.
- Produces: `createSseParser(onEvent)` with `push(chunk)` and `finish()` methods; `mergeStreamText(current, incoming)`; `createAgentStreamState()`; `consumeJsonSseResponse(response, onEvent)`; browser global `window.AiAgentSse`; CommonJS export for Node tests.

- [x] **Step 1: Write failing parser tests**

```javascript
test('parses an event split across chunks', () => {
  const events = [];
  const parser = createSseParser((event) => events.push(event));
  parser.push('event: tr');
  parser.push('ace\ndata: {"agentName":"EV"}\n\n');
  assert.deepEqual(events, [{ event: 'trace', data: '{"agentName":"EV"}' }]);
});
```

Also cover multiple frames per chunk, CRLF, multi-line data, comments, and `finish()` without a trailing blank line.

- [x] **Step 2: Run tests and verify RED**

Run: `node --test docs/dev-ops/nginx/html/js/sse-client.test.js`

Expected: FAIL because `sse-client.js` or its exports do not exist.

- [x] **Step 3: Implement the minimal parser**

Normalize frame separators with `/\r?\n\r?\n/`, retain incomplete text between `push` calls, collect `data:` lines with `\n`, ignore comments and unknown fields, and parse any final buffered frame in `finish()`.

- [x] **Step 4: Write streamed-text merge tests**

```javascript
assert.equal(mergeStreamText('abc', 'abcdef'), 'abcdef');
assert.equal(mergeStreamText('abcdef', 'def'), 'abcdef');
assert.equal(mergeStreamText('abc', 'def'), 'abcdef');
```

Also cover blank current and blank incoming content.

- [x] **Step 5: Implement `mergeStreamText` and verify GREEN**

Run: `node --test docs/dev-ops/nginx/html/js/sse-client.test.js`

Expected: all parser and merge tests pass.

### Task 2: Streaming message view and POST SSE lifecycle

**Files:**
- Modify: `docs/dev-ops/nginx/html/js/index.js`

**Interfaces:**
- Consumes: `window.AiAgentSse.createSseParser`, `mergeStreamText`, and backend JSON events with fields `type`, `agentName`, `content`, `completed`.
- Produces: a single streaming Agent message view with `updateFinal`, `upsertTrace`, `complete`, and `fail` behavior; `sendMessageStream(message, view, signal)`.

- [x] **Step 1: Add stream state and cancellation helper**

Add `activeStreamController` to `state` and an `abortActiveStream()` helper. Call it before a new request, when changing Agent, logging out, and handling `pagehide`.

- [x] **Step 2: Extract a streaming message view**

Create one Agent row containing a trace `details`, trace `summary`, final Markdown container, and streaming cursor. Upsert trace nodes by `agentName`; never append trace content to the final container.

- [x] **Step 3: Implement POST SSE consumption**

Use `fetch('/chat_stream', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body, signal })`. Validate `response.ok`, validate the content type, decode `response.body` with `TextDecoder`, and feed chunks into `createSseParser`.

- [x] **Step 4: Route structured events**

Parse each SSE `data` JSON object. Route `trace` to `upsertTrace`; route `final` and compatibility `event` to `updateFinal`. Record whether a final/event was received so a trace-only completed stream reports a missing final answer.

- [x] **Step 5: Replace synchronous submit flow**

Keep `createSession`, create the streaming view before starting the fetch, await the stream, restore the status and send button in `finally`, and suppress user-facing errors for `AbortError`.

- [x] **Step 6: Run JavaScript syntax checks**

Run:

```powershell
node --check docs/dev-ops/nginx/html/js/sse-client.js
node --check docs/dev-ops/nginx/html/js/index.js
```

Expected: both commands exit 0.

### Task 3: Streaming and trace presentation

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html`

**Interfaces:**
- Consumes: class names produced by the streaming message view.
- Produces: collapsed trace presentation, streaming cursor animation, empty-answer placeholder, and correct script loading order.

- [x] **Step 1: Add trace styles**

Style `.trace-panel`, `.trace-summary`, `.trace-list`, `.trace-item`, and `.trace-agent`; keep the native `details` element closed initially and ensure Markdown content remains readable.

- [x] **Step 2: Add streaming styles**

Style `.stream-answer`, `.stream-placeholder`, `.stream-cursor`, and `.message-row.error`; animate only opacity so layout remains stable.

- [x] **Step 3: Load the parser before the application**

```html
<script src="js/config.js"></script>
<script src="js/sse-client.js"></script>
<script src="js/index.js"></script>
```

- [x] **Step 4: Check HTML references**

Run: `rg -n "sse-client|trace-panel|stream-cursor" docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/index.js`

Expected: the script loads before `index.js`, and generated class names match stylesheet selectors.

### Task 4: Regression verification and documentation

**Files:**
- Modify: `docs/architecture/parallel-agent-output-policy.md`
- Modify: `docs/superpowers/specs/2026-08-07-streaming-chat-frontend-design.md` only if implementation reveals a required clarification.

**Interfaces:**
- Consumes: completed frontend implementation and backend SSE contract.
- Produces: maintainer-facing frontend integration notes and reproducible verification evidence.

- [x] **Step 1: Run frontend tests**

Run: `node --test docs/dev-ops/nginx/html/js/sse-client.test.js`

Expected: all 16 tests pass with zero failures.

- [x] **Step 2: Run backend output-policy regression tests**

Run with JDK 17:

```powershell
mvn -pl ai-agent-scaffold-app -am '-Dtest=OutputModeEnumTest,AgentOutputPolicyValidatorTest,AgentOutputEventMapperTest,AgentChatResultAssemblerTest,AgentStreamSubscriptionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: 18 tests pass with zero failures, errors, or skips.

- [x] **Step 3: Update the architecture record**

Document the POST SSE frontend flow, trace/final rendering behavior, cancellation lifecycle, parser tests, and the fact that `EventSource` is not used because the endpoint is POST.

- [x] **Step 4: Run final static checks**

Run:

```powershell
node --check docs/dev-ops/nginx/html/js/sse-client.js
node --check docs/dev-ops/nginx/html/js/index.js
git diff --check
```

Expected: all commands exit 0; existing line-ending warnings are acceptable, whitespace errors are not.

- [x] **Step 5: Review commit scope**

Run `git status --short` and `git diff --name-status`. Confirm unrelated `.idea`, root Maven, generated data, and other user-owned changes remain untouched and unstaged.
