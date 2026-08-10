# Agent Draw.io 配置设计

## 1. 目标

在 `draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml` 中配置一套面向 Draw.io 绘图对话的多 Agent 工作流。工作流需要完成需求澄清、按需检索、图形结构设计、Draw.io XML 生成和两轮质量检查，并且只向前端返回一个固定结构的 JSON 对象。

最终响应协议只有两种：

```json
{"type":"user","content":"需要用户补充的信息"}
```

```json
{"type":"drawio","content":"完成 JSON 转义的完整 Draw.io XML"}
```

## 2. 范围

本次只新增 `agent-draw-io.yml`，不修改已有的 `draw-io-agent.yml`、`application-dev.yml`、Java 代码或前端代码。当前前端仍会把最终内容直接当作 XML；前端需要在后续改造中先解析上述 JSON，再根据 `type` 展示追问或加载 `content` 中的 XML。

模型连接使用环境变量占位符，不在配置文件中保存明文密钥。MCP 配置保留本地工具，并提供可按部署环境启用的 SSE 和 stdio 示例。

## 3. 方案选择

采用“串行主流程 + 并行理解 + 循环质检”的混合编排：

```text
RequestAnalystAgent
        ↓
ParallelUnderstandingWorkflow
├── ContextResearchAgent
└── DiagramStructureAgent
        ↓
DiagramPlannerAgent
        ↓
DrawioGeneratorAgent
        ↓
DrawioQualityLoop（固定两轮）
        ↓
FinalResponseAgent
```

相比单一 Agent，该方案将需求判断、资料检索、结构设计、绘制和校验隔离，降低提示词职责冲突。相比全串行方案，资料检索与图形结构分析可以并行执行。质检循环使用固定两轮，避免缺少显式退出工具时出现不可控循环。

## 4. 单一 Agent 职责与内部契约

### 4.1 RequestAnalystAgent

解析用户目标、图形对象、受众、范围、必要实体与关系、布局偏好和输出语言，写入 `request_analysis`。内部结果必须是 JSON，并以 `READY` 或 `NEED_MORE_INFO` 表示需求是否足够。

当信息不足时，只提出数量尽可能少且能解除阻塞的问题，不擅自补全会改变图形语义的实体、关系或范围。

### 4.2 ContextResearchAgent

读取 `request_analysis` 并写入 `research_context`。只有在用户请求依赖代码仓库、本地文件、外部事实或明确要求检索时才调用可用 MCP 工具。优先使用与目标直接相关的仓库、文件或权威资料，不把工具不可用或未检索到的信息编造成事实。

当 `request_analysis` 为 `NEED_MORE_INFO` 时，禁止调用工具并输出跳过状态。当工具失败且缺失信息会影响图形正确性时，在内部结果中标记阻塞原因，交给最终响应 Agent 追问用户。

### 4.3 DiagramStructureAgent

读取 `request_analysis` 并写入 `structure_analysis`。独立判断适合的图形类型，包括 UML 类图、时序图、用例图、活动图、状态图，以及流程图、架构图、ER 图、网络拓扑图等；输出建议的实体、关系、分组、方向和布局约束。

### 4.4 DiagramPlannerAgent

合并 `request_analysis`、`research_context` 和 `structure_analysis`，写入 `diagram_plan`。计划明确列出节点、边、容器、标签、样式角色、画布方向、层级或泳道，以及减少交叉连线的排序和路由策略。

当需求状态不是 `READY`，或检索结果标记为阻塞时，只写入跳过原因，不创建猜测性的绘图计划。

### 4.5 DrawioGeneratorAgent

根据 `diagram_plan` 生成未压缩的完整 `<mxfile>` 文档并写入 `drawio_xml`。文档必须包含合法的 `<diagram>`、`<mxGraphModel>`、根 `mxCell`、唯一 ID、顶点几何信息和带有 source/target 的边。禁止 Markdown 代码围栏、解释文字、占位节点和图形外的附加内容。

当绘图计划被跳过时，输出明确的内部跳过标记，不生成空 XML 或伪造 XML。

### 4.6 DrawioQualityAgent

读取当前 `drawio_xml`，检查后将修订后的完整 XML 覆盖回同一个 `drawio_xml`。`DrawioQualityLoop` 固定执行两轮，使第二轮基于第一轮修订结果继续检查。

当输入是跳过标记而不是 XML 时，保持跳过状态，不尝试修复或补画。

检查内容包括：

- XML 标签、属性转义、根结构和 ID 引用完整性；
- 需求中的实体和关系是否遗漏或错误；
- 孤立节点、重复元素和无效边；
- 节点重叠、文本空间不足、边界留白不足；
- 连线交叉、穿越节点、方向混乱和不必要回边；
- UML、流程图等图形的语义和形状使用是否合理。

### 4.7 FinalResponseAgent

这是唯一允许直接向用户输出的 Agent。它读取所有内部结果：

- 需求不足或检索阻塞且无法可靠绘图时，输出 `type=user`；
- 图形已生成并通过两轮检查时，输出 `type=drawio`。

输出必须是单个合法 JSON 对象，只包含字符串字段 `type` 和 `content`。不得使用 Markdown、前后说明、XML 代码围栏或额外字段。`drawio` 类型的 `content` 必须对双引号、反斜杠、换行和控制字符进行标准 JSON 转义，解析后的字符串必须以 `<mxfile` 开始并形成完整 XML。

## 5. 工作流与输出策略

配置按依赖顺序创建三个工作流：

1. `ParallelUnderstandingWorkflow` 并行运行 `ContextResearchAgent` 和 `DiagramStructureAgent`。
2. `DrawioQualityLoop` 循环运行 `DrawioQualityAgent`，`max-iterations` 为 `2`。
3. `DrawioPipeline` 串联需求分析、并行理解、绘图计划、首次生成、循环质检和最终响应。

Runner 配置如下：

- `agent-name: DrawioPipeline`
- `response-agent-name: FinalResponseAgent`
- `output-mode: FINAL_ONLY`

`FINAL_ONLY` 确保其他 Agent 的内部 JSON、检索结果、草稿 XML 和质检过程不会进入前端最终消息。

## 6. 模型、MCP 与安全

AI API 使用 Spring 占位符，例如 `${AI_BASE_URI}`、`${AI_API_KEY}`、`${AI_COMPLETIONS_PATH:chat/completions}`、`${AI_EMBEDDINGS_PATH:embeddings}` 和 `${AI_MODEL}`。配置表和 Agent ID 使用稳定名称，并可为 Agent ID 提供环境变量默认值。

`tool-mcp-list` 默认声明项目已有的本地 `myToolCallbackProvider`。SSE 和 stdio 配置以完整注释模板提供；部署人员启用后，所有单一 Agent 共享 ChatModel 所挂载的工具，但只有 `ContextResearchAgent` 被提示主动做资料检索。stdio 模板可用于 Git 仓库和本地文件检索服务，环境变量通过 `server-parameters.env` 传递。

配置中不得出现实际 API Key、访问令牌或固定的外部 MCP 凭证。

## 7. 失败处理

- 需求含糊：返回 `type=user`，明确指出缺少的信息。
- MCP 不可用：不虚构工具结果；若用户输入本身足够则继续，否则返回 `type=user`。
- 图形类型存在多个合理选择但会显著改变表达：先追问用户；不影响核心语义时由结构 Agent 选择最清晰的类型。
- XML 草稿存在问题：由质量循环修复并覆盖，不把错误草稿暴露给前端。
- 两轮后仍不能形成可靠 XML：最终响应返回 `type=user`，说明需要补充或缩小的范围。

## 8. 验证与验收

实现后执行以下检查：

1. YAML 能被解析，字段名称符合 `AiAgentConfigTableVO`，尤其使用运行时支持的 `base-uri`。
2. 所有单一 Agent 和工作流名称唯一，所有 `sub-agents`、Runner 和响应 Agent 引用均可解析。
3. `FINAL_ONLY` 配置了存在的 `response-agent-name`。
4. `DrawioQualityAgent` 的输入和输出都使用 `drawio_xml`，循环次数为两轮。
5. 最终提示词明确限定两种 JSON 类型和两个字段。
6. XML 生成与检查提示覆盖结构合法性、语义正确性、布局和连线交叉。
7. 文件中不存在形似明文 API Key、Token 或 MCP 凭证的内容。

## 9. 非目标与后续工作

本次不验证真实模型或外部 MCP 的网络调用，因为连接信息由部署环境注入。本次也不修改前端 JSON 分流逻辑；后续前端需要解析最终 JSON，并在 `type=user` 时显示 `content`、在 `type=drawio` 时将 `content` 交给现有 Draw.io XML 校验和画布加载流程。
