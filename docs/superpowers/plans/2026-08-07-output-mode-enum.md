# Output Mode Enum Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep YAML output modes string-based while using `OutputModeEnum` as the validated runtime representation and single source of truth.

**Architecture:** `AiAgentConfigTableVO.Runner` remains the external configuration boundary and stores the YAML value as a string. `AgentOutputPolicyValidator` parses and normalizes that value, `RunnerNode` stores the resulting enum in `AiAgentRegisterVO`, and `AgentOutputEventMapper` branches on the enum instead of duplicated string constants.

**Tech Stack:** Java 17, Spring, Lombok, JUnit 4, Maven

## Global Constraints

- Preserve YAML values `ALL_EVENTS`, `FINAL_ONLY`, and `FINAL_WITH_TRACE`.
- Preserve `ALL_EVENTS` as the default for blank or omitted configuration.
- Do not expose child-agent trace output as model chain-of-thought.
- Do not modify unrelated working-tree changes.

---

### Task 1: Define and test output-mode parsing

**Files:**
- Modify: `ai-agent-scaffold-domain/src/main/java/cn/bugstack/ai/domain/agent/model/valobj/enums/OutputModeEnum.java`
- Create: `ai-agent-scaffold-app/src/test/java/cn/bugstack/ai/test/domain/agent/OutputModeEnumTest.java`

**Interfaces:**
- Consumes: external YAML string values.
- Produces: `Optional<OutputModeEnum> OutputModeEnum.fromCode(String code)` and canonical `getCode()` values.

- [x] **Step 1: Write failing tests** for canonical, case-insensitive, trimmed, null, and unknown values.
- [x] **Step 2: Run `OutputModeEnumTest`** and confirm it fails because `fromCode`/`getCode` do not exist.
- [x] **Step 3: Implement immutable enum fields** named `code` and `description`, plus `fromCode`.
- [x] **Step 4: Run `OutputModeEnumTest`** and confirm it passes.

### Task 2: Parse configuration once at the runtime boundary

**Files:**
- Modify: `ai-agent-scaffold-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/AgentOutputPolicyValidator.java`
- Modify: `ai-agent-scaffold-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/RunnerNode.java`
- Modify: `ai-agent-scaffold-domain/src/main/java/cn/bugstack/ai/domain/agent/model/valobj/AiAgentRegisterVO.java`
- Modify: `ai-agent-scaffold-domain/src/main/java/cn/bugstack/ai/domain/agent/model/valobj/AiAgentConfigTableVO.java`
- Modify: `ai-agent-scaffold-app/src/test/java/cn/bugstack/ai/test/domain/agent/AgentOutputPolicyValidatorTest.java`

**Interfaces:**
- Consumes: `Runner.outputMode` as `String` and available agent names.
- Produces: `OutputModeEnum AgentOutputPolicyValidator.validate(...)` and runtime `AiAgentRegisterVO.outputMode` as `OutputModeEnum`.

- [x] **Step 1: Change validator tests** to assert the returned enum and normalized default code.
- [x] **Step 2: Run validator tests** and confirm compilation fails against the current `void` API.
- [x] **Step 3: Return the parsed enum from validation**, normalize the configuration code, and store the enum in the runtime registration object.
- [x] **Step 4: Run validator tests** and confirm they pass.

### Task 3: Replace mapper magic strings with enum branches

**Files:**
- Modify: `ai-agent-scaffold-domain/src/main/java/cn/bugstack/ai/domain/agent/service/chat/AgentOutputEventMapper.java`
- Modify: `ai-agent-scaffold-app/src/test/java/cn/bugstack/ai/test/domain/agent/AgentOutputEventMapperTest.java`

**Interfaces:**
- Consumes: validated runtime `OutputModeEnum` from `AiAgentRegisterVO`.
- Produces: unchanged `EVENT`, `TRACE`, and `FINAL` classification behavior.

- [x] **Step 1: Change mapper tests** to construct registrations with enum values.
- [x] **Step 2: Run mapper tests** and confirm compilation fails while runtime registration still expects strings.
- [x] **Step 3: Remove mapper string constants** and compare `OutputModeEnum` values, retaining null as legacy `ALL_EVENTS`.
- [x] **Step 4: Run focused output-policy tests**, then all existing focused agent-output tests.
- [x] **Step 5: Run `git diff --check`** on the touched files.
