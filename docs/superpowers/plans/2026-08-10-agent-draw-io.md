# Agent Draw.io Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增一个可由 Spring Agent 配置框架装配的 `agent-draw-io.yml`，通过需求澄清、并行检索与结构分析、绘图、两轮质检和最终格式化，稳定输出 `user` 或 `drawio` JSON 消息。

**Architecture:** `DrawioPipeline` 是串行入口，其中 `ParallelUnderstandingWorkflow` 并行执行资料检索和图形结构分析，`DrawioQualityLoop` 对同一个 `drawio_xml` 状态执行两轮修订。`FinalResponseAgent` 是唯一响应 Agent，Runner 使用 `FINAL_ONLY` 隔离所有内部输出。

**Tech Stack:** Spring Boot YAML 配置、项目现有 Google ADK Agent/ParallelAgent/LoopAgent/SequentialAgent 装配、Node.js、`js-yaml`、PowerShell、Maven/JUnit。

## Global Constraints

- 只新增 `draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml`；不修改 `draw-io-agent.yml`、`application-dev.yml`、Java 或前端源码。
- 最终消息只能是 `{"type":"user","content":"..."}` 或 `{"type":"drawio","content":"..."}`，只能包含 `type` 和 `content` 两个字符串字段。
- `drawio` 的 `content` 是完成标准 JSON 转义的完整、未压缩 `<mxfile>` XML。
- AI API 和可选 MCP 凭证只能使用环境变量占位符，不得写入明文密钥。
- 运行时字段使用 `base-uri`，不能沿用 `demo.yml` 中与 Java 属性不一致的 `base-url`。
- 当前前端尚未解析该 JSON；本计划不扩展到前端适配。

---

## File Structure

- Create: `draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml` — 模型、MCP、七个单一 Agent、三个组合工作流和 Runner 的唯一配置来源。

### Task 1: Create and verify the Draw.io agent configuration

**Files:**
- Create: `draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml`
- Reference: `draw-io-front-harry-app/src/main/resources/agent/demo.yml`
- Reference: `docs/superpowers/specs/2026-08-10-agent-draw-io-design.md`

**Interfaces:**
- Consumes: Spring 环境变量 `DRAW_IO_AGENT_ID`, `AI_BASE_URI`, `AI_API_KEY`, `AI_COMPLETIONS_PATH`, `AI_EMBEDDINGS_PATH`, `AI_MODEL`；项目本地 MCP Bean `myToolCallbackProvider`。
- Produces: 配置表 `agentDrawIo`、根工作流 `DrawioPipeline`、响应 Agent `FinalResponseAgent` 和固定 JSON 消息协议。

- [ ] **Step 1: Run the precondition check and observe the missing file failure**

Run from `D:/javaAI/draw-io-front-harry`:

```powershell
node -e "require('fs').accessSync('D:/javaAI/draw-io-back-harry/draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml')"
```

Expected: FAIL with `ENOENT`, proving the requested configuration does not already exist and no user file will be overwritten.

- [ ] **Step 2: Create the complete YAML configuration**

Create the file with this exact configuration graph:

```yaml
ai.agent.config.tables.agentDrawIo.module:
  agents:
    - RequestAnalystAgent        # output-key: request_analysis
    - ContextResearchAgent       # output-key: research_context
    - DiagramStructureAgent      # output-key: structure_analysis
    - DiagramPlannerAgent        # output-key: diagram_plan
    - DrawioGeneratorAgent       # output-key: drawio_xml
    - DrawioQualityAgent         # output-key: drawio_xml
    - FinalResponseAgent
  agent-workflows:
    - ParallelUnderstandingWorkflow
    - DrawioQualityLoop
    - DrawioPipeline
  runner:
    agent-name: DrawioPipeline
    response-agent-name: FinalResponseAgent
    output-mode: FINAL_ONLY
```

The actual file must use the nested schema demonstrated by `demo.yml`, not the dotted shorthand shown above. Configure:

```yaml
enabled: true
tables:
  agentDrawIo:
    app-name: agentDrawIo
    agent:
      agent-id: "${DRAW_IO_AGENT_ID:100005}"
      agent-name: Draw.io 智能绘图
      agent-desc: 对绘图诉求进行澄清、检索、规划、生成和质量检查
    module:
      ai-api:
        base-uri: "${AI_BASE_URI}"
        api-key: "${AI_API_KEY}"
        completions-path: "${AI_COMPLETIONS_PATH:chat/completions}"
        embeddings-path: "${AI_EMBEDDINGS_PATH:embeddings}"
      chat-model:
        model: "${AI_MODEL}"
```

Add the active local MCP entry `myToolCallbackProvider`, the resource skills entry `agent/skills`, and fully commented SSE and stdio MCP examples that use only environment variables.

Use the following internal response contracts verbatim in the relevant instructions:

```json
{"status":"READY|NEED_MORE_INFO","normalizedRequest":"","diagramGoal":"","diagramTypeHint":"","requiredFacts":[],"entities":[],"relations":[],"constraints":[],"clarificationQuestion":""}
```

```json
{"status":"COMPLETE|SKIPPED|BLOCKED","sourcesUsed":[],"facts":[],"constraints":[],"blockReason":""}
```

```json
{"status":"COMPLETE|SKIPPED","diagramType":"","direction":"TB|LR","groups":[],"nodes":[],"edges":[],"layoutRules":[]}
```

```json
{"status":"READY|SKIPPED","diagramType":"","canvas":{"direction":"TB|LR","width":0,"height":0},"groups":[],"nodes":[],"edges":[],"layoutRules":[],"skipReason":""}
```

Prompt requirements by Agent:

1. `RequestAnalystAgent` returns `NEED_MORE_INFO` whenever missing information would change entities, relationships, diagram scope or diagram type; its clarification question must be minimal and specific.
2. `ContextResearchAgent` inspects available MCP tools and invokes them only for repository, local-file, external-fact or explicit-search needs. It must skip when the request is incomplete and return `BLOCKED` rather than invent unavailable facts.
3. `DiagramStructureAgent` chooses among UML class/sequence/use-case/activity/state, flowchart, architecture, ER and network topology diagrams and independently proposes topology and layout.
4. `DiagramPlannerAgent` merges `{request_analysis}`, `{research_context}` and `{structure_analysis}`, and skips on incomplete or blocked input.
5. `DrawioGeneratorAgent` consumes `{diagram_plan}` and returns only a full uncompressed `<mxfile>` document when ready; otherwise it returns the exact internal marker `SKIPPED: drawing prerequisites are incomplete`.
6. `DrawioQualityAgent` consumes `{drawio_xml}`, preserves the skip marker, otherwise returns only corrected XML. It checks XML structure, escaping, unique IDs, references, semantics, overlaps, text space, margins, isolated nodes, edge directions, edge crossings, node crossings and unnecessary back edges.
7. `FinalResponseAgent` consumes `{request_analysis}`, `{research_context}`, `{diagram_plan}` and `{drawio_xml}`. It returns only one JSON object with exactly `type` and `content`; no Markdown or explanation. It emits `user` for incomplete/blocked/skipped states and `drawio` only for a complete `<mxfile>` value, with standard JSON escaping.

Configure workflows in dependency order:

```yaml
- type: parallel
  name: ParallelUnderstandingWorkflow
  sub-agents: [ContextResearchAgent, DiagramStructureAgent]
- type: loop
  name: DrawioQualityLoop
  max-iterations: 2
  sub-agents: [DrawioQualityAgent]
- type: sequential
  name: DrawioPipeline
  sub-agents:
    - RequestAnalystAgent
    - ParallelUnderstandingWorkflow
    - DiagramPlannerAgent
    - DrawioGeneratorAgent
    - DrawioQualityLoop
    - FinalResponseAgent
```

- [ ] **Step 3: Parse the YAML and verify the configuration graph**

Run from `D:/javaAI/draw-io-front-harry`:

```powershell
@'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const yaml = require('js-yaml');
const file = 'D:/javaAI/draw-io-back-harry/draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml';
const source = fs.readFileSync(file, 'utf8');
const document = yaml.load(source);
const config = document.ai.agent.config;
const table = config.tables.agentDrawIo;
const module = table.module;
const expectedAgents = [
  'RequestAnalystAgent', 'ContextResearchAgent', 'DiagramStructureAgent',
  'DiagramPlannerAgent', 'DrawioGeneratorAgent', 'DrawioQualityAgent',
  'FinalResponseAgent'
];
const agents = new Map(module.agents.map((agent) => [agent.name, agent]));
assert.equal(config.enabled, true);
assert.deepEqual([...agents.keys()], expectedAgents);
assert.equal(module['ai-api']['base-uri'], '${AI_BASE_URI}');
assert.equal(module['ai-api']['api-key'], '${AI_API_KEY}');
assert.equal(module['chat-model'].model, '${AI_MODEL}');
assert.equal(agents.get('RequestAnalystAgent')['output-key'], 'request_analysis');
assert.equal(agents.get('ContextResearchAgent')['output-key'], 'research_context');
assert.equal(agents.get('DiagramStructureAgent')['output-key'], 'structure_analysis');
assert.equal(agents.get('DiagramPlannerAgent')['output-key'], 'diagram_plan');
assert.equal(agents.get('DrawioGeneratorAgent')['output-key'], 'drawio_xml');
assert.equal(agents.get('DrawioQualityAgent')['output-key'], 'drawio_xml');
const workflows = new Map(module['agent-workflows'].map((workflow) => [workflow.name, workflow]));
assert.deepEqual(workflows.get('ParallelUnderstandingWorkflow')['sub-agents'], ['ContextResearchAgent', 'DiagramStructureAgent']);
assert.equal(workflows.get('DrawioQualityLoop')['max-iterations'], 2);
assert.deepEqual(workflows.get('DrawioQualityLoop')['sub-agents'], ['DrawioQualityAgent']);
assert.deepEqual(workflows.get('DrawioPipeline')['sub-agents'], [
  'RequestAnalystAgent', 'ParallelUnderstandingWorkflow', 'DiagramPlannerAgent',
  'DrawioGeneratorAgent', 'DrawioQualityLoop', 'FinalResponseAgent'
]);
assert.deepEqual(module.runner, {
  'agent-name': 'DrawioPipeline',
  'response-agent-name': 'FinalResponseAgent',
  'output-mode': 'FINAL_ONLY'
});
const finalPrompt = agents.get('FinalResponseAgent').instruction;
assert.match(finalPrompt, /"type":"user"/);
assert.match(finalPrompt, /"type":"drawio"/);
assert.match(finalPrompt, /恰好两个字段|exactly two fields/i);
assert.match(agents.get('ContextResearchAgent').instruction, /MCP/);
assert.match(agents.get('DrawioQualityAgent').instruction, /交叉/);
for (const line of source.split(/\r?\n/)) {
  const match = line.match(/^\s*(api-key|[^#]*token[^:]*):\s*(.+)\s*$/i);
  if (match) assert.match(match[2], /^"?\$\{[A-Z0-9_]+(?::[^}]*)?\}"?$/);
}
console.log('PASS: agent-draw-io.yml syntax and contract verified');
'@ | node
```

Expected: `PASS: agent-draw-io.yml syntax and contract verified`.

- [ ] **Step 4: Run the existing Runner output-policy tests**

Run from `D:/javaAI/draw-io-back-harry`:

```powershell
$env:JAVA_HOME='C:\Users\Harry\.jdks\dragonwell-ex-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl draw-io-front-harry-app -am '-Dtest=AgentOutputPolicyValidatorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Expected: Reactor build succeeds and `AgentOutputPolicyValidatorTest` reports zero failures and errors, confirming that `FINAL_ONLY` with a declared response Agent remains supported.

- [ ] **Step 5: Run whitespace, placeholder and scope checks**

```powershell
git -C 'D:\javaAI\draw-io-back-harry' diff --check -- 'draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml'
git -C 'D:\javaAI\draw-io-back-harry' status --short -- 'draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml' 'draw-io-front-harry-app/src/main/resources/agent/draw-io-agent.yml' 'draw-io-front-harry-app/src/main/resources/application-dev.yml'
```

Expected: `diff --check` has no output. Status shows the new `agent-draw-io.yml`; this task does not add new modifications to `draw-io-agent.yml` or `application-dev.yml` beyond any pre-existing user changes.

- [ ] **Step 6: Commit only the new configuration**

```powershell
git -C 'D:\javaAI\draw-io-back-harry' add -- 'draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml'
git -C 'D:\javaAI\draw-io-back-harry' commit --only -m "feat: configure draw.io agent pipeline" -- 'draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml'
```

Expected: one commit containing only `agent-draw-io.yml`; all unrelated staged and working-tree changes remain untouched.
