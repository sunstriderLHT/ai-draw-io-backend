# AI Agent Scaffold

[中文文档](README.cn.md)

AI Agent Scaffold is a Java 17, Spring Boot, DDD-oriented scaffold for configuring and running AI agents. It combines agent configuration, runner assembly, MCP tools, session management, and a lightweight Nginx-hosted chat UI.

## Highlights

- Configure single agents, sequential workflows, and parallel workflows with YAML.
- Assemble agents, models, tools, plugins, and runners dynamically through the armory layer.
- Support local, SSE, and stdio MCP transports.
- Create sessions isolated by `agentId + userId` and run synchronous or streaming conversations.
- Provide a static login and chat UI with agent selection, Markdown rendering, and per-message session creation.

## Architecture

| Module | Responsibility |
| --- | --- |
| `ai-agent-scaffold-api` | Public service contracts, DTOs, and response envelope |
| `ai-agent-scaffold-app` | Spring Boot application, profiles, and runtime wiring |
| `ai-agent-scaffold-domain` | Agent domain model, session service, workflows, and armory |
| `ai-agent-scaffold-trigger` | HTTP adapters and `AgentServiceController` |
| `ai-agent-scaffold-infrastructure` | Persistence and external infrastructure adapters |
| `ai-agent-scaffold-types` | Shared enums, exceptions, and utilities |

## Prerequisites

- JDK 17
- Maven 3.9+
- MySQL instance configured for the selected Spring profile
- A valid LLM or gateway configuration in the selected agent YAML files

## Run the backend

1. Review `ai-agent-scaffold-app/src/main/resources/application-dev.yml`.
2. Configure your own database connection and agent/model credentials. Do not commit credentials.
3. Start the application:

```powershell
mvn -pl ai-agent-scaffold-app -am spring-boot:run
```

The development profile runs on `http://127.0.0.1:8091` by default.

## Static chat UI

The static UI lives in `docs/dev-ops/nginx/html`:

- `login.html`: demo login page, default account `admin` / `admin`.
- `index.html`: agent selection and chat workspace.
- `js/config.js`: frontend API base URL.
- `js/index.js`: login guard, session creation, chat requests, and Markdown rendering.

Host this directory with Nginx and open `/login.html`. The frontend calls `http://127.0.0.1:8091/api/v1` by default. The demo login uses Cookie storage and has a `localStorage` fallback only for local `file://` previews. It is not production authentication.

## HTTP API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/query_ai_agent_config_list` | List available agents |
| `POST` | `/api/v1/create_session` | Create or obtain a session for `agentId` and `userId` |
| `POST` | `/api/v1/chat` | Send a synchronous chat request |
| `POST` | `/api/v1/chat_stream` | Send a streaming chat request |

`create_session` and `chat` request bodies use the following fields:

```json
{
  "agentId": "100002",
  "userId": "admin",
  "sessionId": "optional-for-create-session",
  "message": "Hello"
}
```

## Development notes

- Sessions are cached per `agentId + userId`; changing agents creates or retrieves that agent's own session.
- The static UI creates a session before every submitted message, then sends the returned ID to `/chat`.
- Build failures reporting an invalid target release indicate that Maven is not using JDK 17.

## Security

- Never commit API keys, database passwords, or MCP credentials.
- Replace the demo login with server-side authentication before deploying publicly.
- Restrict CORS and network exposure in production.

## License

This repository inherits its license and upstream terms from the project owner.
