# Draw.io Deterministic Final Response Design

## Goal

Ensure a `READY` request always enters the drawing workflow even when an LLM wraps JSON in a Markdown code fence, and ensure only the drawing pipeline—not the final response component—can create Draw.io XML.

## Confirmed Failure

Invocation `e-15829c32-51cf-4be8-802e-f0e80a60257e` returned a fenced `RequestAnalystAgent` payload beginning with ` ```json `. `ReadyDrawingWorkflow` then started and completed in the same millisecond without starting any child Agent. `FinalResponseAgent`, which was still a generic ADK `LlmAgent`, spent 50 seconds and 13,067 tokens generating an `<mxfile agent="final-response-agent">` document itself.

## Design

### Shared session-state JSON parsing

Introduce one parser used by conditional routing and final response handling. It accepts:

- a plain JSON string;
- a JSON string enclosed by a complete Markdown `json` code fence;
- an already structured state value such as a map.

Malformed, incomplete, or prose-prefixed values remain invalid. The conditional workflow fails closed and runs no children for invalid state.

### Deterministic final response Agent

Replace the YAML-declared `FinalResponseAgent` LLM with a Java `DrawioFinalResponseAgent extends BaseAgent`. It performs no model or tool calls. It reads session state and emits exactly one JSON response event authored as `FinalResponseAgent`.

Response priority is deterministic:

1. If `request_analysis.status` is `NEED_MORE_INFO`, return `type=user` with `clarificationQuestion`. Any old `drawio_xml` is ignored.
2. If request analysis is missing, malformed, or has an unsupported status, return `type=user` with a retry explanation.
3. If status is `READY` but `drawio_xml` is absent, a skip marker, a retry marker, or not a complete `<mxfile>...</mxfile>` document, return `type=user` with a useful failure explanation.
4. Only a complete `drawio_xml` produces `type=drawio`; Jackson serializes the JSON so XML quotes, newlines, and control characters are escaped correctly.

### Workflow assembly

Add a `drawio-final-response` workflow type and assembly Node. The YAML removes `FinalResponseAgent` from the generic LLM `agents` list, declares a workflow named `FinalResponseAgent`, and retains it as the runner's `response-agent-name`.

The root remains:

```text
RequestAnalystAgent
  -> ReadyDrawingWorkflow (only when parsed status == READY)
  -> FinalResponseAgent (deterministic Java Agent)
```

## Compatibility

- The frontend `{type, content}` contract is unchanged.
- `FINAL_WITH_TRACE` behavior is unchanged because the deterministic event author remains `FinalResponseAgent`.
- Existing sequential, parallel, loop, and conditional workflows remain unchanged.
- No frontend modification is required.

## Testing

- Reproduce the fenced READY payload and verify conditional children run.
- Verify `NEED_MORE_INFO` returns `user` even when stale XML exists.
- Verify READY plus valid XML returns the exact XML as `drawio`.
- Verify READY without valid XML never returns `drawio`.
- Verify malformed analysis never returns `drawio`.
- Verify YAML assembly uses the deterministic workflow instead of an LLM Agent.
- Run output mapper regressions and Maven package without live LLM or MCP calls.
