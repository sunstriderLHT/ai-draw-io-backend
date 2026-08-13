# Draw.io Generic Final Response Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the generic LLM final-response workflow while fixing conditional routing for Markdown-fenced JSON state.

**Architecture:** Keep `SessionStateJsonParser` as a reusable workflow utility consumed by `ConditionalAgent`. Remove Draw.io-specific Java Agent/node types and express the final `{type, content}` policy entirely in `agent-draw-io.yml`.

**Tech Stack:** Java 17, Spring, Google ADK, Jackson, JUnit 4, Maven, YAML.

## Global Constraints

- Java workflow infrastructure must not reference Draw.io state keys or response policy.
- `FinalResponseAgent` remains a YAML-configured LLM Agent.
- A single complete JSON fence may have explanatory prose around it; invalid or ambiguous condition state fails closed.
- Preserve unrelated staged and unstaged workspace changes.

---

### Task 1: Restore Generic Final Response Assembly

**Files:**
- Delete: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/DrawioFinalResponseAgent.java`
- Delete: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/DrawioFinalResponseAgentNode.java`
- Modify: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/model/valobj/enums/AgentTypeEnum.java`
- Modify: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/AgentWorkflowNode.java`
- Modify: `draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml`
- Modify: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/app/DrawioAgentConfigTest.java`
- Restore: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/app/DrawioFinalResponseInstructionTest.java`
- Delete: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/domain/agent/DrawioFinalResponseAgentTest.java`
- Delete: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/domain/agent/DrawioFinalResponseAgentNodeTest.java`

**Interfaces:**
- Consumes: YAML `agents[]`, `agent-workflows[]`, and runner `response-agent-name` configuration.
- Produces: `FinalResponseAgent` as a generic LLM Agent referenced by the sequential `DrawioPipeline`.

- [ ] **Step 1: Change the configuration test to require an LLM final Agent**

Assert `module.agents[6].name == FinalResponseAgent`, `module.agent-workflows[3].type == sequential`, and the root children remain `RequestAnalystAgent`, `ReadyDrawingWorkflow`, `FinalResponseAgent`.

- [ ] **Step 2: Run the configuration test and verify it fails**

Run:

```powershell
mvn -pl draw-io-front-harry-app -am "-Dtest=DrawioAgentConfigTest,DrawioFinalResponseInstructionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: failure because the current working tree declares `drawio-final-response` and has no LLM `FinalResponseAgent` instruction.

- [ ] **Step 3: Remove the Draw.io-specific Java workflow extension**

Delete both specialized classes, remove `DrawioFinalResponse` from `AgentTypeEnum`, and remove the `drawioFinalResponseAgentNode` injection and switch branch from `AgentWorkflowNode`.

- [ ] **Step 4: Restore the YAML LLM Agent**

Restore a `FinalResponseAgent` entry under `agents` with optional placeholders `{research_context?}`, `{diagram_plan?}`, and `{drawio_xml?}`. Remove the `drawio-final-response` workflow entry so `DrawioPipeline` returns to workflow index 3.

- [ ] **Step 5: Restore the optional-state instruction regression test**

Use `InstructionUtils.injectSessionState` with only `request_analysis` present and assert injection succeeds without leaving `{research_context`, `{diagram_plan`, or `{drawio_xml` placeholders.

- [ ] **Step 6: Run the configuration tests and verify they pass**

Run the command from Step 2. Expected: all selected tests pass.

### Task 2: Verify Generic Fenced-JSON Routing

**Files:**
- Retain: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/SessionStateJsonParser.java`
- Retain: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/ConditionalAgent.java`
- Retain: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/domain/agent/ConditionalAgentTest.java`

**Interfaces:**
- Consumes: session state as plain JSON, one complete Markdown `json` fence with optional surrounding prose, or structured objects.
- Produces: a boolean condition match without any Draw.io-specific knowledge.

- [ ] **Step 1: Run focused workflow and configuration regressions**

Before the focused suite, reproduce the production response shape in `ConditionalAgentTest`: explanatory Chinese prose followed by a `json` fence containing `{"status":"READY"}` must invoke the conditional child exactly once.

```powershell
mvn -pl draw-io-front-harry-app -am "-Dtest=ConditionalAgentTest,ConditionalAgentNodeTest,DrawioAgentConfigTest,DrawioFinalResponseInstructionTest,AgentOutputEventMapperTest,AgentOutputPolicyValidatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: all selected tests pass with zero failures and zero errors.

- [ ] **Step 2: Build every Maven module**

```powershell
mvn -DskipTests package
```

Expected: reactor `BUILD SUCCESS` for all seven modules.

- [ ] **Step 3: Check change scope**

Run `git diff --check` and `git status --short`. Confirm no Draw.io-specific final-response Java class remains and unrelated user changes are untouched.
