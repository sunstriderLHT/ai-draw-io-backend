# Draw.io Request Short-Circuit Design

## Goal

Stop the Draw.io pipeline immediately after `RequestAnalystAgent` when its output status is `NEED_MORE_INFO`. The workflow must proceed directly to `FinalResponseAgent` without invoking research, structure, planning, generation, or quality Agents.

## Root Cause

`DrawioPipeline` is currently an ADK `SequentialAgent`. ADK executes every child with an unconditional sequential `concatMap`. Prompt instructions that tell downstream Agents to return `BLOCKED` or `SKIPPED` still invoke their language models, so they do not save latency or tokens.

## Design

Add a reusable `conditional` workflow type to the existing workflow assembler. It evaluates a field in JSON stored in the ADK session state and runs its configured child Agents sequentially only when the configured value matches.

The Draw.io workflow will be reorganized as:

1. `RequestAnalystAgent` always runs and writes `request_analysis`.
2. `ReadyDrawingWorkflow` checks `request_analysis.status == READY`.
3. When the condition matches, it runs the existing research, structure, planning, generation, and quality workflow.
4. When the condition does not match, it emits no events and invokes no child Agents.
5. `FinalResponseAgent` always runs after the conditional workflow and converts the analysis result or approved XML to the frontend response contract.

Conceptually:

```text
RequestAnalystAgent
  -> ReadyDrawingWorkflow (only when request_analysis.status == READY)
       -> ParallelUnderstandingWorkflow
       -> DiagramPlannerAgent
       -> DrawioGeneratorAgent
       -> DrawioQualityLoop
  -> FinalResponseAgent
```

## Configuration Contract

The reusable workflow configuration adds these fields:

```yaml
- type: conditional
  name: ReadyDrawingWorkflow
  condition-state-key: request_analysis
  condition-json-field: status
  condition-expected-value: READY
  sub-agents:
    - ParallelUnderstandingWorkflow
    - DiagramPlannerAgent
    - DrawioGeneratorAgent
    - DrawioQualityLoop
```

All condition fields are required for `conditional`. A missing state value, invalid JSON value, absent field, or non-matching field safely skips the conditional children. Configuration errors such as blank condition names fail during assembly rather than invoking Agents unpredictably.

## Runtime Behavior

For `NEED_MORE_INFO`, the only LLM calls are `RequestAnalystAgent` and `FinalResponseAgent`. The final response remains `{"type":"user","content":"..."}` and no placeholder `SKIPPED` traces are generated for Agents that did not run.

For `READY`, behavior remains equivalent to the current complete drawing workflow, including stage traces and the final `drawio` response.

This iteration deliberately does not add later short-circuit gates for `BLOCKED` or `SKIPPED` states.

## Testing

- Unit-test the conditional Agent with matching `READY`, non-matching `NEED_MORE_INFO`, missing state, and malformed JSON.
- Test configuration parsing and assembly for the new workflow type.
- Extend the Draw.io YAML test to assert the root ordering and the `request_analysis.status == READY` condition.
- Run the existing backend output policy and Draw.io configuration tests to verify that `FINAL_WITH_TRACE` and final-response routing remain intact.

## Safety and Compatibility

Existing `loop`, `parallel`, and `sequential` configurations are unchanged. The conditional Agent reads session state only and does not mutate it. The frontend SSE contract requires no change.
