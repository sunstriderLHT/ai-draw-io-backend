# Draw.io Generic Final Response Design

## Goal

Fix fenced JSON routing without placing Draw.io response policy inside the generic Java workflow framework.

## Root Cause

`RequestAnalystAgent` returned a valid `READY` object inside a Markdown `json` code fence. `ConditionalAgent` attempted to parse the complete fenced string as raw JSON, so `ReadyDrawingWorkflow` skipped every drawing child. The downstream LLM `FinalResponseAgent` then generated XML because the drawing state was missing.

## Design

Keep the framework change limited to reusable session-state parsing:

- `ConditionalAgent` accepts plain JSON, complete Markdown `json` fences, and structured state values.
- Malformed, incomplete, prose-prefixed, and non-JSON fenced values remain invalid and fail closed.
- `FinalResponseAgent` remains a YAML-configured generic LLM Agent.
- The `{type, content}` response rules and Draw.io-specific state keys remain in `agent-draw-io.yml` rather than Java classes.
- Remove the Draw.io-specific workflow type, assembly node, deterministic Agent, and their specialized tests.

The workflow remains:

```text
RequestAnalystAgent
  -> ReadyDrawingWorkflow (only when parsed status == READY)
  -> FinalResponseAgent (generic LLM Agent configured by YAML)
```

## Testing

- A fenced `READY` result must execute conditional children.
- `NEED_MORE_INFO`, malformed JSON, and missing state must skip drawing children.
- YAML must retain the LLM `FinalResponseAgent` and the sequential root workflow.
- The final instruction must tolerate optional drawing state after a short circuit.
- Run focused workflow/configuration regressions and a full Maven package.
