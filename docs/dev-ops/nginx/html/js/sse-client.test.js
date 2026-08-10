const test = require('node:test');
const assert = require('node:assert/strict');

const {
  consumeJsonSseResponse,
  createAgentStreamState,
  createSseParser,
  mergeStreamText,
} = require('./sse-client.js');

test('parses an SSE event split across network chunks', () => {
  const events = [];
  const parser = createSseParser((event) => events.push(event));

  parser.push('event: tr');
  parser.push('ace\ndata: {"agentName":"EVResearcher"}\n\n');

  assert.deepEqual(events, [{
    event: 'trace',
    data: '{"agentName":"EVResearcher"}',
  }]);
});

test('parses multiple CRLF-delimited events from one chunk', () => {
  const events = [];
  const parser = createSseParser((event) => events.push(event));

  parser.push(
    'event: trace\r\ndata: first\r\n\r\n' +
    'event: final\r\ndata: second\r\n\r\n',
  );

  assert.deepEqual(events, [
    { event: 'trace', data: 'first' },
    { event: 'final', data: 'second' },
  ]);
});

test('joins multiple data lines and ignores comments and unknown fields', () => {
  const events = [];
  const parser = createSseParser((event) => events.push(event));

  parser.push(
    ': heartbeat\n' +
    'id: 42\n' +
    'event: final\n' +
    'data: line one\n' +
    'data: line two\n\n',
  );

  assert.deepEqual(events, [{
    event: 'final',
    data: 'line one\nline two',
  }]);
});

test('flushes a final event without a trailing blank line', () => {
  const events = [];
  const parser = createSseParser((event) => events.push(event));

  parser.push('event: final\ndata: complete');
  parser.finish();

  assert.deepEqual(events, [{ event: 'final', data: 'complete' }]);
});

test('ignores frames that contain no data field', () => {
  const events = [];
  const parser = createSseParser((event) => events.push(event));

  parser.push(': heartbeat\n\nevent: final\n\n');

  assert.deepEqual(events, []);
});

test('replaces current text when incoming text is cumulative', () => {
  assert.equal(mergeStreamText('abc', 'abcdef'), 'abcdef');
});

test('ignores an incoming fragment already present at the end', () => {
  assert.equal(mergeStreamText('abcdef', 'def'), 'abcdef');
});

test('appends a new incremental fragment', () => {
  assert.equal(mergeStreamText('abc', 'def'), 'abcdef');
});

test('handles blank current or incoming text', () => {
  assert.equal(mergeStreamText('', 'answer'), 'answer');
  assert.equal(mergeStreamText('answer', ''), 'answer');
  assert.equal(mergeStreamText(null, null), '');
});

test('keeps trace output separate and replaces the same agent trace', () => {
  const state = createAgentStreamState();

  const first = state.apply('trace', {
    agentName: 'EVResearcher',
    content: 'first result',
    completed: true,
  });
  const second = state.apply('trace', {
    agentName: 'EVResearcher',
    content: 'updated result',
    completed: true,
  });

  assert.equal(first.kind, 'trace');
  assert.equal(second.trace.content, 'updated result');
  assert.deepEqual(state.snapshot(), {
    content: '',
    traces: [{
      agentName: 'EVResearcher',
      content: 'updated result',
      completed: true,
    }],
    hasAnswer: false,
  });
});

test('merges final events into one answer', () => {
  const state = createAgentStreamState();

  state.apply('final', { content: 'abc', completed: false });
  const result = state.apply('final', { content: 'abcdef', completed: true });

  assert.deepEqual(result, { kind: 'answer', content: 'abcdef' });
  assert.equal(state.snapshot().hasAnswer, true);
});

test('treats compatibility event as answer content', () => {
  const state = createAgentStreamState();

  const result = state.apply('event', { content: 'legacy answer' });

  assert.deepEqual(result, { kind: 'answer', content: 'legacy answer' });
});

test('ignores unknown event types without changing state', () => {
  const state = createAgentStreamState();

  const result = state.apply('ping', { content: 'ignored' });

  assert.deepEqual(result, { kind: 'ignored' });
  assert.deepEqual(state.snapshot(), {
    content: '',
    traces: [],
    hasAnswer: false,
  });
});

test('cancels the response body when content type is not SSE', async () => {
  let cancelled = false;
  const body = new ReadableStream({
    cancel() { cancelled = true; },
  });
  const response = new Response(body, {
    headers: { 'Content-Type': 'application/json' },
  });

  await assert.rejects(
    consumeJsonSseResponse(response, () => {}),
    /Content-Type/,
  );

  assert.equal(cancelled, true);
});

test('cancels the stream when an SSE data field contains invalid JSON', async () => {
  let cancelled = false;
  const encoder = new TextEncoder();
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode('event: final\ndata: not-json\n\n'));
    },
    cancel() { cancelled = true; },
  });
  const response = new Response(body, {
    headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
  });

  await assert.rejects(
    consumeJsonSseResponse(response, () => {}),
    /JSON/,
  );

  assert.equal(cancelled, true);
});

test('decodes JSON SSE events from a response stream', async () => {
  const encoder = new TextEncoder();
  const events = [];
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode(
        'event: trace\ndata: {"agentName":"EVResearcher","content":"result"}\n\n',
      ));
      controller.close();
    },
  });
  const response = new Response(body, {
    headers: { 'Content-Type': 'text/event-stream' },
  });

  await consumeJsonSseResponse(response, (event) => events.push(event));

  assert.deepEqual(events, [{
    event: 'trace',
    payload: { agentName: 'EVResearcher', content: 'result' },
  }]);
});
