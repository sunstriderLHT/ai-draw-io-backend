(function (root, factory) {
  const api = factory();

  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.AiAgentSse = api;
  }
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  function parseFrame(frame) {
    let eventName = 'message';
    const dataLines = [];

    frame.split(/\r?\n/).forEach((line) => {
      if (!line || line.startsWith(':')) return;

      const separatorIndex = line.indexOf(':');
      const field = separatorIndex < 0 ? line : line.slice(0, separatorIndex);
      let value = separatorIndex < 0 ? '' : line.slice(separatorIndex + 1);
      if (value.startsWith(' ')) value = value.slice(1);

      if (field === 'event') eventName = value || 'message';
      if (field === 'data') dataLines.push(value);
    });

    if (dataLines.length === 0) return null;
    return { event: eventName, data: dataLines.join('\n') };
  }

  function createSseParser(onEvent) {
    if (typeof onEvent !== 'function') {
      throw new TypeError('onEvent must be a function');
    }

    let buffer = '';

    function emitFrame(frame) {
      const event = parseFrame(frame);
      if (event) onEvent(event);
    }

    function drainFrames() {
      let boundary = buffer.match(/\r?\n\r?\n/);
      while (boundary) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        emitFrame(frame);
        boundary = buffer.match(/\r?\n\r?\n/);
      }
    }

    return {
      push(chunk) {
        if (chunk === null || chunk === undefined || chunk === '') return;
        buffer += String(chunk);
        drainFrames();
      },
      finish() {
        drainFrames();
        if (buffer) emitFrame(buffer);
        buffer = '';
      },
    };
  }

  function mergeStreamText(current, incoming) {
    const currentText = current === null || current === undefined ? '' : String(current);
    const incomingText = incoming === null || incoming === undefined ? '' : String(incoming);

    if (!incomingText) return currentText;
    if (!currentText) return incomingText;
    if (incomingText.startsWith(currentText)) return incomingText;
    if (currentText.endsWith(incomingText)) return currentText;
    return currentText + incomingText;
  }

  function createAgentStreamState() {
    let content = '';
    let hasAnswer = false;
    const traces = new Map();

    function normalizeEventName(eventName, payload) {
      const explicitName = String(eventName || '').toLowerCase();
      if (explicitName && explicitName !== 'message') return explicitName;
      return String(payload && payload.type ? payload.type : explicitName).toLowerCase();
    }

    return {
      apply(eventName, payload) {
        const eventPayload = payload || {};
        const normalizedName = normalizeEventName(eventName, eventPayload);

        if (normalizedName === 'trace') {
          const trace = {
            agentName: eventPayload.agentName || 'Research Agent',
            content: eventPayload.content || '',
            completed: Boolean(eventPayload.completed),
          };
          traces.set(trace.agentName, trace);
          return { kind: 'trace', trace: { ...trace } };
        }

        if (normalizedName === 'final' || normalizedName === 'event') {
          content = mergeStreamText(content, eventPayload.content);
          hasAnswer = Boolean(content.trim());
          return { kind: 'answer', content };
        }

        return { kind: 'ignored' };
      },
      snapshot() {
        return {
          content,
          traces: Array.from(traces.values(), (trace) => ({ ...trace })),
          hasAnswer,
        };
      },
    };
  }

  async function consumeJsonSseResponse(response, onEvent) {
    if (typeof onEvent !== 'function') {
      throw new TypeError('onEvent must be a function');
    }

    const contentType = response.headers.get('Content-Type') || '';
    if (!contentType.toLowerCase().includes('text/event-stream')) {
      if (response.body && !response.body.locked) {
        await response.body.cancel();
      }
      throw new Error(`SSE response has invalid Content-Type: ${contentType || 'unknown'}`);
    }
    if (!response.body) throw new Error('SSE response body is not readable');

    const parser = createSseParser((sseEvent) => {
      let payload;
      try {
        payload = JSON.parse(sseEvent.data);
      } catch (error) {
        throw new Error(`Unable to parse SSE JSON: ${error.message}`);
      }
      onEvent({ event: sseEvent.event, payload });
    });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        parser.push(decoder.decode(value, { stream: true }));
      }
      parser.push(decoder.decode());
      parser.finish();
    } catch (error) {
      try {
        await reader.cancel(error);
      } catch (cancelError) {
        // Preserve the original protocol, callback, or network error.
      }
      throw error;
    } finally {
      reader.releaseLock();
    }
  }

  return {
    consumeJsonSseResponse,
    createAgentStreamState,
    createSseParser,
    mergeStreamText,
  };
}));
