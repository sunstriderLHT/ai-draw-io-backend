# 后端鉴权与额度管理运维手册

本文档用于配置和运维 Draw.io AI 后端的 Supabase JWT 鉴权、用户白名单、模型服务、额度管理与过期预占恢复任务。

> 文档中的 `<...>` 均为占位符。不要把真实密钥、Token 或数据库密码写入本文档或提交到 Git。

## 1. 部署前安全处理

已经写入 Git 历史的模型 API Key 必须在模型服务商控制台吊销，并重新生成新 Key。

禁止把真实凭据写入以下位置：

- Git 仓库
- `application-*.yml`
- Dockerfile
- Docker Compose 配置
- 启动日志

后端验证 Supabase JWT 只需要公开的 issuer 和 JWKS 地址，不需要以下敏感凭据：

- Supabase `service_role` Key
- Supabase Secret Key
- JWT Secret

## 2. 必需环境变量

### 2.1 Supabase 鉴权

```env
SUPABASE_ISSUER=https://<project-ref>.supabase.co/auth/v1
SUPABASE_JWKS_URI=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
AI_ALLOWED_USER_IDS=<user-uuid-1>,<user-uuid-2>
```

`AI_ALLOWED_USER_IDS` 是允许使用 AI 服务的 Supabase 用户 UUID。多个 UUID 使用英文逗号分隔，不要使用邮箱作为白名单身份。

用户 UUID 的获取路径：

```text
Supabase Dashboard
→ Authentication
→ Users
→ 选择用户
→ User UID
```

后端只信任 JWT 的 `sub` UUID，不信任前端请求体中的 `userId`。

### 2.2 额度配置

```env
AI_QUOTA_FREE=3
AI_RESERVATION_TIMEOUT=PT10M
AI_RESERVATION_RECOVERY_INTERVAL=PT1M
```

配置含义：

- `AI_QUOTA_FREE`：新用户初始免费次数。
- `AI_RESERVATION_TIMEOUT`：预占超过该时长后允许自动释放。
- `AI_RESERVATION_RECOVERY_INTERVAL`：后台恢复任务的执行间隔。

时长使用 ISO-8601 Duration 格式。例如：

- `PT1M`：1 分钟
- `PT10M`：10 分钟
- `PT1H`：1 小时

### 2.3 模型配置

```env
AI_MODEL_API_KEY=<rotated-model-key>
AI_BASE_URI=https://<model-provider-host>/<compatible-api-root>
AI_MODEL=<model-name>
AI_COMPLETIONS_PATH=chat/completions
AI_EMBEDDINGS_PATH=embeddings
```

注意事项：

- `AI_BASE_URI` 是模型服务商的 OpenAI 兼容 API 根地址。
- `AI_BASE_URI` 不应包含 `chat/completions`。
- 建议 `AI_BASE_URI` 不以 `/` 结尾。
- `AI_COMPLETIONS_PATH` 建议不以 `/` 开头。
- `AI_MODEL_API_KEY` 必须使用已经轮换的新 Key。

对应的 Spring YAML 配置如下：

```yaml
ai:
  agent:
    config:
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
              api-key: "${AI_MODEL_API_KEY}"
              completions-path: "${AI_COMPLETIONS_PATH:chat/completions}"
              embeddings-path: "${AI_EMBEDDINGS_PATH:embeddings}"
            chat-model:
              model: "${AI_MODEL}"
```

`${NAME:default}` 表示环境变量不存在时使用默认值；`${NAME}` 没有默认值，应用启动前必须设置。

### 2.4 Draw.io Agent 配置

根据服务器的实际安装位置配置：

```env
DRAW_IO_AGENT_ID=<agent-id>
DRAW_IO_LOCAL_FILE_ROOT=<absolute-directory>
DRAW_IO_REPOSITORY=<repository-path>

DRAW_IO_MCP_STDIO_NAME=<stdio-name>
DRAW_IO_MCP_STDIO_COMMAND=<stdio-command>
DRAW_IO_MCP_STDIO_ARG=<stdio-argument>
DRAW_IO_MCP_STDIO_REQUEST_TIMEOUT=30

DRAW_IO_MCP_SSE_NAME=<sse-name>
DRAW_IO_MCP_SSE_BASE_URI=<sse-base-uri>
DRAW_IO_MCP_SSE_ENDPOINT=<sse-endpoint>
DRAW_IO_MCP_SSE_REQUEST_TIMEOUT=30000
```

## 3. 如何传入环境变量

### 3.1 本地 PowerShell

环境变量只对当前 PowerShell 窗口及其启动的子进程生效：

```powershell
$env:SUPABASE_ISSUER='https://<project-ref>.supabase.co/auth/v1'
$env:SUPABASE_JWKS_URI='https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json'
$env:AI_ALLOWED_USER_IDS='<user-uuid-1>,<user-uuid-2>'

$env:AI_MODEL_API_KEY='<rotated-model-key>'
$env:AI_BASE_URI='https://<model-provider-host>/<compatible-api-root>'
$env:AI_MODEL='<model-name>'
```

不要在终端输出真实 Key。可以只检查它是否已设置：

```powershell
if ($env:AI_MODEL_API_KEY) {
    'AI_MODEL_API_KEY is configured'
}
```

设置后必须在同一个 PowerShell 窗口中启动 Maven 或应用。

### 3.2 IDEA

打开运行配置：

```text
Run
→ Edit Configurations
→ 选择 Spring Boot Application
→ Environment variables
```

逐项添加环境变量。不要把真实凭据保存到会被 Git 跟踪的共享 Run Configuration。

### 3.3 云服务器 Docker

在后端部署目录创建 `.env`，只保存在服务器上：

```env
SUPABASE_ISSUER=https://<project-ref>.supabase.co/auth/v1
SUPABASE_JWKS_URI=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
AI_ALLOWED_USER_IDS=<user-uuid-1>,<user-uuid-2>

AI_QUOTA_FREE=3
AI_RESERVATION_TIMEOUT=PT10M
AI_RESERVATION_RECOVERY_INTERVAL=PT1M

AI_MODEL_API_KEY=<rotated-model-key>
AI_BASE_URI=https://<model-provider-host>/<compatible-api-root>
AI_MODEL=<model-name>
AI_COMPLETIONS_PATH=chat/completions
AI_EMBEDDINGS_PATH=embeddings
```

限制文件权限：

```bash
chmod 600 .env
```

Docker Compose 的后端服务应显式读取该文件：

```yaml
services:
  backend:
    env_file:
      - .env
```

启动时也可以显式指定 Compose 环境文件：

```bash
sudo docker compose --env-file .env up -d --build
```

## 4. 数据库连接

生产环境通过环境变量覆盖 Spring 数据源配置：

```env
SPRING_PROFILES_ACTIVE=dev
SPRING_DATASOURCE_URL=jdbc:mysql://<mysql-host>:3306/<database>?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false
SPRING_DATASOURCE_USERNAME=<database-user>
SPRING_DATASOURCE_PASSWORD=<database-password>
```

数据库账号需要拥有目标数据库的建表、查询、插入和更新权限。

## 5. 初始化额度表

创建目标数据库：

```sql
CREATE DATABASE <database>
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
```

应用启动时，Flyway 会自动执行：

```text
db/migration/V1__create_ai_quota_tables.sql
```

启动日志应出现类似信息：

```text
Successfully applied 1 migration
```

确认表已创建：

```sql
SHOW TABLES LIKE 'ai_user_quota';
SHOW TABLES LIKE 'ai_quota_ledger';
```

不要在已经由 Flyway 管理的数据库中手工重复执行迁移 SQL。

## 6. 用户白名单

从 Supabase Authentication Users 页面复制用户 UUID，然后设置：

```env
AI_ALLOWED_USER_IDS=<uuid-1>,<uuid-2>
```

修改后重启后端。

鉴权结果应符合以下规则：

- 没有 JWT：HTTP 401。
- JWT 无效：HTTP 401。
- JWT 合法但用户不在白名单：HTTP 403。
- JWT 合法且用户在白名单：允许访问。
- 前端请求体中的 `userId` 不作为可信身份。

## 7. 查询用户额度

查询账户额度：

```sql
SELECT
    user_id,
    free_granted,
    purchased_granted,
    consumed,
    reserved,
    free_granted + purchased_granted
        - consumed - reserved AS remaining,
    updated_at
FROM ai_user_quota
WHERE user_id = '<example-user-uuid>';
```

查询额度流水：

```sql
SELECT
    request_id,
    agent_id,
    endpoint,
    entry_type,
    amount,
    status,
    created_at,
    updated_at
FROM ai_quota_ledger
WHERE user_id = '<example-user-uuid>'
ORDER BY id DESC;
```

流水状态：

- `RESERVED`：请求已预占次数。
- `COMMITTED`：请求成功并正式扣减。
- `RELEASED`：请求失败、取消或超时，预占已释放。

## 8. API 验收

后端仅监听服务器内部端口 `8091`，安全组不得开放公网 TCP 8091。

主要接口：

```text
GET  /api/v1/quota
POST /api/v1/chat
POST /api/v1/chat_stream
```

受保护请求必须携带 Supabase Access Token：

```http
Authorization: Bearer <supabase-access-token>
```

两个聊天接口还必须携带幂等键：

```http
Idempotency-Key: <new-canonical-uuid>
```

同一次业务重试必须复用同一个 `Idempotency-Key`；新的业务请求必须生成新的 UUID。

预期错误状态：

- `400`：参数或 `Idempotency-Key` 不合法。
- `401`：JWT 缺失或无效。
- `402`：体验次数已用完。
- `403`：用户不在白名单。
- `409`：相同请求正在处理或已经完成。

## 9. 回滚公网入口

后端验证完成之前，Nginx 保持 API 入口返回 404：

```nginx
location = /api/v1 {
    return 404;
}

location ^~ /api/v1/ {
    return 404;
}
```

重新加载 Nginx：

```bash
sudo nginx -t
sudo nginx -s reload
```

使用 Docker Compose 部署时可以执行：

```bash
sudo docker compose -f deploy/docker-compose.yml restart nginx
```

这样前端仍可访问，但公网请求不会转发到后端，也不会消耗模型 Token。

## 10. 故障排查

额度长期处于 `RESERVED` 时，依次检查：

1. 后端是否启用了 Spring Scheduling。
2. `AI_RESERVATION_TIMEOUT` 是否为合法的 ISO-8601 Duration。
3. `AI_RESERVATION_RECOVERY_INTERVAL` 是否为合法的 ISO-8601 Duration。
4. 应用和数据库是否使用 UTC 时间。
5. 日志是否出现“已释放过期额度预占”。

禁止直接删除额度流水记录。需要人工处理时，应先备份相关账户和流水数据。

## 11. 部署前检查清单

- [ ] 已吊销曾经进入 Git 历史的模型 Key。
- [ ] 已在服务器生成并配置新的模型 Key。
- [ ] `.env` 权限为 `600`，并且未被 Git 跟踪。
- [ ] Supabase issuer、JWKS URI 和用户白名单已配置。
- [ ] TCP 8091 未向公网开放。
- [ ] Flyway 迁移成功，额度表已创建。
- [ ] 401、402、403、409 响应符合 API 契约。
- [ ] Nginx 在联调完成前继续对 `/api/v1` 返回 404。
