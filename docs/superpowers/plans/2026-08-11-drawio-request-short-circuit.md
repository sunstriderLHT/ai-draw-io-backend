# Draw.io Request Short-Circuit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reusable conditional workflow Node so an incomplete Draw.io request skips all expensive drawing Agents and proceeds directly to `FinalResponseAgent`.

**Architecture:** A new ADK `ConditionalAgent` reads a configured JSON field from session state. A matching condition executes its children sequentially; a missing, malformed, or non-matching value returns an empty event stream. The existing armory assembler gains a `conditional` workflow Node, and the Draw.io root pipeline becomes analyst → conditional drawing workflow → final response.

**Tech Stack:** Java 21, Spring Boot, Google ADK 1.1.0, RxJava 3, Jackson, JUnit 4, Maven, YAML.

## Global Constraints

- Only `request_analysis.status == READY` gates the expensive drawing workflow in this iteration.
- `NEED_MORE_INFO` must invoke no research, structure, planning, generation, or quality Agent.
- `FinalResponseAgent` always runs.
- Existing `loop`, `parallel`, and `sequential` workflow behavior remains unchanged.
- The frontend SSE contract remains unchanged.

---

### Task 1: Conditional Agent Runtime

**Files:**
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/ConditionalAgent.java`
- Create: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/domain/agent/ConditionalAgentTest.java`

**Interfaces:**
- Consumes: `InvocationContext.session().state()` and configured `stateKey`, `jsonField`, `expectedValue` strings.
- Produces: `ConditionalAgent(String name, String description, List<? extends BaseAgent> subAgents, String stateKey, String jsonField, String expectedValue)`; matching state concatenates child events, otherwise returns `Flowable.empty()`.

- [ ] **Step 1: Write failing runtime tests**

Create a real counting `BaseAgent` test child and assert these literal outcomes:

```java
assertEquals(1, runWithState("{\"status\":\"READY\"}").size());
assertEquals(0, runWithState("{\"status\":\"NEED_MORE_INFO\"}").size());
assertEquals(0, runWithState("not-json").size());
assertEquals(0, runWithoutState().size());
```

The test must assert the emitted event author for the matching branch so removal or inversion of the condition fails the test.

- [ ] **Step 2: Run the runtime test and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Users\Harry\.jdks\dragonwell-ex-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -s C:\Users\Harry\.m2\settings.xml -pl draw-io-front-harry-app -am '-Dtest=ConditionalAgentTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: compilation fails because `ConditionalAgent` does not exist.

- [ ] **Step 3: Implement the minimal runtime Agent**

Extend `BaseAgent`, pass configured children to `super`, parse a textual JSON state value with Jackson, compare the configured field, and use `Flowable.fromIterable(subAgents()).concatMap(agent -> agent.runAsync(context))` only on a match. `runLiveImpl` applies the same gate and invokes `agent.runLive(context)`.

- [ ] **Step 4: Run the runtime test and verify GREEN**

Run the Task 1 Maven command and expect all `ConditionalAgentTest` cases to pass.

### Task 2: Armory Workflow Assembly

**Files:**
- Modify: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/model/valobj/AiAgentConfigTableVO.java`
- Modify: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/model/valobj/enums/AgentTypeEnum.java`
- Modify: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/AgentWorkflowNode.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/agent/service/armory/node/workflow/ConditionalAgentNode.java`
- Create: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/domain/agent/ConditionalAgentNodeTest.java`

**Interfaces:**
- Consumes: YAML-bound `conditionStateKey`, `conditionJsonField`, and `conditionExpectedValue` on `AiAgentConfigTableVO.Module.AgentWorkflow`.
- Produces: `AgentTypeEnum.Conditional` routed to Spring bean `conditionalAgentNode`; the node registers the built conditional Agent under the workflow name.

- [ ] **Step 1: Write failing assembly tests**

Test that a configured workflow creates a `ConditionalAgent` with the configured children and that blank condition values throw `IllegalArgumentException` before registration.

- [ ] **Step 2: Run the assembly test and verify RED**

Run the Task 1 Maven command with `-Dtest=ConditionalAgentNodeTest`; expect compilation failure because the Node and configuration accessors do not exist.

- [ ] **Step 3: Implement minimal assembly support**

Add the three configuration fields, enum entry, injected routing branch, and `ConditionalAgentNode`. Validate nonblank name, state key, JSON field, expected value, and at least one resolved child before creating the runtime Agent.

- [ ] **Step 4: Run runtime and assembly tests and verify GREEN**

Run with `-Dtest=ConditionalAgentTest,ConditionalAgentNodeTest` and expect zero failures.

### Task 3: Draw.io Workflow Configuration

**Files:**
- Modify: `draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml`
- Modify: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/app/DrawioAgentConfigTest.java`

**Interfaces:**
- Consumes: the `conditional` workflow contract from Task 2.
- Produces: root `DrawioPipeline` ordered as `RequestAnalystAgent`, `ReadyDrawingWorkflow`, `FinalResponseAgent`.

- [ ] **Step 1: Add a failing YAML behavior test**

Load the YAML through `YamlPropertySourceLoader` and assert the hand-derived configuration: conditional state key `request_analysis`, field `status`, expected value `READY`, and root children exactly analyst/gate/final.

- [ ] **Step 2: Run the YAML test and verify RED**

Run with `-Dtest=DrawioAgentConfigTest`; expect failure because the conditional configuration is absent.

- [ ] **Step 3: Reorganize the YAML**

Add `ReadyDrawingWorkflow` after its referenced parallel and loop workflows. Move the expensive children into it, leaving the root sequential workflow with only analyst, conditional workflow, and final response.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run with `-Dtest=ConditionalAgentTest,ConditionalAgentNodeTest,DrawioAgentConfigTest`; expect zero failures.

### Task 4: Regression Verification

**Files:**
- Verify only; no new production files.

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: evidence that conditional routing, output mapping, and existing configuration remain compatible.

- [ ] **Step 1: Run focused backend regression tests**

```powershell
mvn -s C:\Users\Harry\.m2\settings.xml -pl draw-io-front-harry-app -am '-Dtest=ConditionalAgentTest,ConditionalAgentNodeTest,DrawioAgentConfigTest,AgentOutputEventMapperTest,AgentOutputPolicyValidatorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

- [ ] **Step 2: Run a compile/package verification without integration LLM calls**

```powershell
mvn -s C:\Users\Harry\.m2\settings.xml -pl draw-io-front-harry-app -am -DskipTests package
```

- [ ] **Step 3: Inspect exact diffs**

Use `git diff --check` and targeted `git diff -- <files>` to confirm no credentials, logs, IDE files, or unrelated user changes were introduced by this implementation.
