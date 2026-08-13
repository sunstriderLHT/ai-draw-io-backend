# 前端流式对话设计规格

## 目标

将现有同步 `/chat` 前端切换为 POST `/chat_stream`，在同一个 Agent 消息气泡中实时更新最终答案，并将子 Agent 的调研结果按 Agent 分组放入默认收起的“调研过程”区域。

## 范围

本次修改只涉及静态前端：

- 新增无依赖的 SSE 解析与文本合并模块；
- 修改聊天请求、流生命周期和消息渲染；
- 增加流式状态及调研折叠区样式；
- 使用 Node 内置测试工具验证纯函数和协议解析。

不修改后端 SSE 协议，不引入 npm 依赖或前端构建系统，不展示模型隐藏思维链。

## 技术方案

采用原生 `fetch`、`ReadableStream` 和 `AbortController` 消费 POST SSE。

不使用原生 `EventSource`，因为 `EventSource` 只适合 GET，无法直接承载当前聊天接口的 JSON 请求体。不引入 `fetch-event-source`，因为当前前端是无构建器的静态 HTML，引入外部依赖会增加部署和供应链成本。

## 文件边界

### `docs/dev-ops/nginx/html/js/sse-client.js`

提供与 DOM 无关的能力：

- 增量接收网络文本块；
- 按空行切分 SSE 帧；
- 支持 `event:`、多行 `data:`、CRLF 和流结束残余缓冲区；
- 将 JSON 数据交给调用方；
- 合并累计文本和增量文本，避免重复显示；
- 校验 SSE Content-Type，并在协议、JSON、回调或读取错误时取消底层响应流；
- 同时暴露浏览器全局对象和 CommonJS 导出，便于静态页面与 Node 测试共用同一实现。

### `docs/dev-ops/nginx/html/js/index.js`

- 创建会话后 POST `/chat_stream`；
- 校验 HTTP 状态，并将 SSE 响应交给协议模块消费；
- 创建可增量更新的 Agent 消息视图；
- 收到 `trace` 时按 `agentName` 覆盖或新增轨迹；
- 收到 `final` 时合并并更新主答案；
- 收到兼容 `event` 时更新主答案；
- 在切换 Agent、退出、页面卸载和异常时取消活动请求；
- 流正常结束后恢复发送按钮；
- 流结束但没有最终输出时保留 trace，并显示明确错误。

### `docs/dev-ops/nginx/html/index.html`

- 在 `index.js` 前加载 `sse-client.js`；
- 增加默认收起的 trace 区域样式；
- 增加流式光标、空答案占位和错误状态样式；
- 保持现有响应式布局。

### `docs/dev-ops/nginx/html/js/sse-client.test.js`

使用 Node 24 内置 `node:test` 和 `assert`，不新增 package.json。

## 数据流

```text
提交消息
  -> 创建 session
  -> 创建空 Agent 气泡
  -> fetch POST /chat_stream
  -> ReadableStream 文本块
  -> SSE parser
     -> trace: upsert 到折叠调研区
     -> final: 合并并更新主答案
     -> event: 兼容模式下合并并更新主答案
  -> 流完成
  -> 移除光标并恢复交互
```

## 文本合并规则

后端事件内容可能是累计文本，也可能是增量片段。`mergeStreamText(current, incoming)` 使用以下规则：

1. `incoming` 为空时保持当前内容；
2. 当前内容为空时直接使用 `incoming`；
3. `incoming` 以当前内容开头时，将其视为累计内容并替换当前内容；
4. 当前内容以 `incoming` 结尾时，将其视为重复片段并忽略；
5. 其他情况将 `incoming` 视为增量片段并追加。

## 渲染规则

- 每次用户提问只创建一个 Agent 主消息气泡；
- 最终答案区域持续更新，不为每个 `final` 事件新增消息；
- trace 区域使用原生 `details/summary`，默认收起；
- summary 显示当前调研 Agent 数量；
- 同一 Agent 的 trace 使用最新内容覆盖旧内容；
- trace 与 final 都通过现有 Markdown 渲染器输出；
- 所有用户和 Agent 文本继续经过现有 HTML 转义逻辑。

## 取消和并发

- `state.activeStreamController` 保存当前请求的 `AbortController`；
- 发送过程中保持发送按钮禁用，避免同一页面并行发送两条消息；
- 切换 Agent 时取消当前流并清空 session；
- 退出登录和 `pagehide` 时取消当前流；
- `AbortError` 视为主动取消，不显示为服务端异常；
- 新请求开始前防御性取消遗留请求。

## 错误处理

- 非 2xx 响应：读取可用错误正文并抛出请求错误；
- Content-Type 不包含 `text/event-stream`：抛出协议错误；
- JSON data 无法解析：终止流并显示数据格式错误；
- Content-Type 或 JSON 等客户端协议错误：取消响应流，触发服务端断连清理；
- 网络读取失败：保留已经显示的 trace/final，停止光标并显示错误；
- 正常结束但没有收到 `final` 或兼容 `event`：保留 trace，提示“智能体没有返回最终内容”。

## 测试标准

`sse-client.test.js` 必须覆盖：

- 一个事件跨多个网络块；
- 一个网络块包含多个事件；
- CRLF 与 LF 帧分隔符；
- 多行 `data:` 合并；
- 流结束时解析无尾随空行的残余帧；
- 忽略注释和不相关字段；
- 累计内容替换；
- 重复片段忽略；
- 增量片段追加。

同时运行现有后端 18 个输出策略聚焦测试，确认前端改动没有伴随协议端回归。

## 验收标准

- 浏览器发送消息时调用 `/chat_stream`，不再调用 `/chat`；
- trace 到达后出现默认收起的“调研过程（N）”；
- final 到达后同一主气泡实时更新；
- 累计或重复事件不会造成正文重复；
- 切换 Agent 或离开页面会中止活动请求；
- 流结束后按钮和状态恢复；
- Node SSE 测试与后端输出策略测试全部通过。
