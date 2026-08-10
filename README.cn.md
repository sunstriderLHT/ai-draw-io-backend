# AI Agent Scaffold

[English](README.md)

AI Agent Scaffold 是一个基于 Java 17、Spring Boot 和 DDD 分层的 AI 智能体工程脚手架。它将智能体配置、运行器装配、MCP 工具、会话管理和 Nginx 静态对话页整合在同一项目中。

## 项目能力

- 通过 YAML 配置单智能体、顺序工作流和并行工作流。
- 由 Armory 动态装配智能体、模型、工具、插件和 Runner。
- 支持 Local、SSE、Stdio 三种 MCP 传输方式。
- 按 `agentId + userId` 隔离会话，支持同步与流式对话。
- 提供静态登录和对话页面，可选择智能体、渲染 Markdown，并在每次提问前创建会话。

## 模块说明

| 模块 | 职责 |
| --- | --- |
| `ai-agent-scaffold-api` | 服务接口、DTO 与统一响应对象 |
| `ai-agent-scaffold-app` | Spring Boot 启动、环境配置和运行时装配 |
| `ai-agent-scaffold-domain` | 智能体领域模型、会话服务、工作流和 Armory |
| `ai-agent-scaffold-trigger` | HTTP 入站适配器和 `AgentServiceController` |
| `ai-agent-scaffold-infrastructure` | 持久化与外部基础设施适配器 |
| `ai-agent-scaffold-types` | 枚举、异常和通用工具 |

## 环境要求

- JDK 17
- Maven 3.9+
- 与当前 Spring Profile 匹配的 MySQL
- 已在智能体 YAML 中配置可用的模型或网关

## 启动后端

1. 检查 `ai-agent-scaffold-app/src/main/resources/application-dev.yml`。
2. 配置你自己的数据库连接、模型和智能体参数。不要提交密钥或密码。
3. 在项目根目录执行：

```powershell
mvn -pl ai-agent-scaffold-app -am spring-boot:run
```

开发环境默认端口为 `8091`，地址为 `http://127.0.0.1:8091`。

## 静态登录与对话页

静态文件位于 `docs/dev-ops/nginx/html`：

- `login.html`：演示登录页，默认账号密码为 `admin / admin`。
- `index.html`：全屏对话页，包含智能体选择、会话信息和 Markdown 回答渲染。
- `js/config.js`：前端接口根地址。
- `js/index.js`：登录检查、会话创建、对话请求和 Markdown 渲染。

建议使用 Nginx 托管该目录，并从 `/login.html` 访问。默认前端接口为 `http://127.0.0.1:8091/api/v1`。本地以 `file://` 预览时会使用 `localStorage` 兜底，正式 Nginx 部署依赖 Cookie。该登录逻辑仅用于演示，不能视为生产鉴权。

## HTTP 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/v1/query_ai_agent_config_list` | 查询可用智能体 |
| `POST` | `/api/v1/create_session` | 为 `agentId` 和 `userId` 创建或获取会话 |
| `POST` | `/api/v1/chat` | 发起同步对话 |
| `POST` | `/api/v1/chat_stream` | 发起流式对话 |

`create_session` 的请求体：

```json
{
  "agentId": "100002",
  "userId": "admin"
}
```

`chat` 的请求体：

```json
{
  "agentId": "100002",
  "userId": "admin",
  "sessionId": "创建会话后返回的 ID",
  "message": "你好"
}
```

## 开发说明

- 后端会话缓存键为 `agentId + userId`，切换智能体不会复用其他智能体的会话。
- 前端每次提交消息都会先调用 `create_session`，再将返回的 `sessionId` 发送给 `/chat`。
- 如果 Maven 提示“无效的目标发行版: 17”，说明 Maven 当前没有使用 JDK 17。

## 安全提醒

- 不要提交 API Key、数据库密码或 MCP 凭据。
- 对外部署前请替换演示登录为服务端认证。
- 生产环境应限制 CORS 允许源和网络暴露范围。
