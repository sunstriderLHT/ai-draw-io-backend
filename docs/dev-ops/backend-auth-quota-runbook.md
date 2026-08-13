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
      - ${DRAWIO_BACKEND_ENV_FILE:-.env}
```

生产环境使用仓库提供的 `docker-compose-production.yml`，显式指定 Compose 环境文件并且只启动后端服务：

```bash
sudo docker compose --env-file .env \
  -f docker-compose-production.yml up -d backend
```

该 Compose 文件只通过 `expose` 向 Docker 网络开放 `8091`，不会把 `8091` 发布到宿主机。

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

## 12. 生产环境私网部署与回滚

本节命令在服务器的 `/home/ubuntu/drawio-backend` 部署目录执行。生产部署只更新后端镜像和后端容器，不删除容器卷、证书、环境文件、数据库或外部网络。

### 12.1 构建发布镜像

先在完成代码检出的构建目录使用 Java 17 打包。只有 Maven 成功后，才允许执行镜像构建：

```bash
export JAVA_HOME=<jdk-17-directory>
export PATH="$JAVA_HOME/bin:$PATH"

if ! mvn -q -pl draw-io-front-harry-app -am -DskipTests package; then
  echo "错误：Maven 打包失败，禁止构建生产镜像" >&2
  exit 1
fi

if sudo docker image inspect drawio-backend:latest >/dev/null 2>&1; then
  if ! sudo docker tag \
    drawio-backend:latest \
    drawio-backend:before-production-deploy; then
    echo "错误：无法创建回滚镜像标签，停止部署" >&2
    exit 1
  fi
else
  echo "当前没有 drawio-backend:latest，首次部署不生成回滚标签"
fi

if ! sudo docker build \
  -f draw-io-front-harry-app/Dockerfile \
  -t drawio-backend:latest \
  draw-io-front-harry-app; then
  echo "错误：生产镜像构建失败，停止部署" >&2
  exit 1
fi
```

`draw-io-front-harry-app/Dockerfile` 会把 Maven 已测试并打包的 JAR 写入镜像。不要把宿主机上的可变 JAR bind mount 到生产容器。

### 12.2 准备部署目录和私网

把仓库中的 `docs/dev-ops/docker-compose-production.yml` 放到部署目录，并创建日志目录：

```bash
cd /home/ubuntu/drawio-backend
install -m 644 \
  <release-directory>/docs/dev-ops/docker-compose-production.yml \
  ./docker-compose-production.yml
mkdir -p ./log
```

只在网络不存在时创建，已有网络不会被修改：

```bash
sudo docker network inspect deploy_app >/dev/null 2>&1 \
  || sudo docker network create deploy_app

sudo docker network inspect software_my-network >/dev/null 2>&1 \
  || sudo docker network create software_my-network
```

在 Compose 文件旁创建或复制服务器专用 `.env`，不要在终端回显其中的值：

```bash
install -m 600 <server-private-env-source> ./.env
test "$(stat -c '%a' .env)" = "600"
```

`.env` 必须保留在服务器，不能进入 Git 或镜像层。

### 12.3 启动后端

先确认 Compose 文件能成功解析且服务名为 `backend`。该命令本身不验证运行时网络成员或宿主机监听端口，网络和端口必须按下一节的实际状态检查：

```bash
if ! compose_services="$(sudo docker compose --env-file .env \
  -f docker-compose-production.yml config --services)"; then
  echo "错误：生产 Compose 无法解析，停止部署" >&2
  exit 1
fi

if [ "$compose_services" != "backend" ]; then
  echo "错误：生产 Compose 服务清单不符合预期，停止部署" >&2
  exit 1
fi

if ! sudo docker compose --env-file .env \
  -f docker-compose-production.yml up -d backend; then
  echo "错误：后端容器启动失败，停止部署" >&2
  exit 1
fi

if ! sudo docker compose --env-file .env \
  -f docker-compose-production.yml ps backend; then
  echo "错误：无法读取后端容器状态" >&2
  exit 1
fi
```

该命令可能重建 `drawio-backend` 容器，但不会执行 `down`，也不会删除卷或外部网络。

### 12.4 验证双网络和私有端口

确认后端同时加入前端入口网络和数据库网络：

```bash
for network in deploy_app software_my-network; do
  if ! network_members="$(sudo docker network inspect "$network" \
    --format '{{range $id, $container := .Containers}}{{println $container.Name}}{{end}}')"; then
    echo "错误：无法检查 Docker 网络 $network" >&2
    exit 1
  fi

  if ! printf '%s\n' "$network_members" | grep -Fx drawio-backend >/dev/null; then
    echo "错误：drawio-backend 未加入 $network" >&2
    exit 1
  fi
done
```

从 `deploy_app` 内部访问受保护接口。缺少 JWT 时应返回 HTTP 401，响应类型和正文应为 JSON：

```bash
if ! unauth_response="$(sudo docker run --rm --network deploy_app \
  curlimages/curl:8.10.1 \
  -sS -w '\nSTATUS=%{http_code}\nTYPE=%{content_type}\n' \
  http://drawio-backend:8091/api/v1/query_ai_agent_config_list)"; then
  echo "错误：内部未鉴权请求失败" >&2
  exit 1
fi

printf '%s\n' "$unauth_response" | grep -Fx 'STATUS=401' >/dev/null \
  || { echo "错误：未鉴权请求没有返回 401" >&2; exit 1; }
printf '%s\n' "$unauth_response" | grep -E '^TYPE=application/json' >/dev/null \
  || { echo "错误：401 响应不是 JSON" >&2; exit 1; }
printf '%s\n' "$unauth_response" | grep -F '"code":"AUTH_TOKEN_INVALID"' >/dev/null \
  || { echo "错误：401 JSON 缺少 AUTH_TOKEN_INVALID" >&2; exit 1; }

echo "内部未鉴权请求通过：STATUS=401，JSON AUTH_TOKEN_INVALID"
```

确认宿主机没有监听 `8091`：

```bash
if ! ss_output="$(sudo ss -lntp)"; then
  echo "错误：无法读取宿主机监听端口" >&2
  exit 1
fi

if printf '%s\n' "$ss_output" \
  | grep -qE '(^|[[:space:]])[^[:space:]]*:8091[[:space:]]'; then
  echo "错误：宿主机正在监听 8091" >&2
  exit 1
else
  echo "宿主机未监听 8091"
fi
```

### 12.5 验证启动、迁移、Mapper 和额度回收任务

查看本次启动日志，确认 Flyway 和 Spring Boot 正常启动。任一标志缺失都会停止验收：

```bash
if ! startup_logs="$(sudo docker compose --env-file .env \
  -f docker-compose-production.yml logs --since 10m backend)"; then
  echo "错误：无法读取后端日志" >&2
  exit 1
fi

printf '%s\n' "$startup_logs" \
  | grep -E 'Successfully applied|Schema .* is up to date' >/dev/null \
  || { echo "错误：未找到 Flyway 成功标志" >&2; exit 1; }
printf '%s\n' "$startup_logs" | grep -F 'Started Application' >/dev/null \
  || { echo "错误：未找到应用启动成功标志" >&2; exit 1; }
```

至少等待一个 `AI_RESERVATION_RECOVERY_INTERVAL`，再确认 MyBatis Mapper 和额度回收定时任务没有异常：

```bash
if ! recovery_logs="$(sudo docker compose --env-file .env \
  -f docker-compose-production.yml logs --since 10m backend)"; then
  echo "错误：无法读取额度回收任务日志" >&2
  exit 1
fi

if printf '%s\n' "$recovery_logs" \
  | grep -E 'BindingException|Invalid bound statement|Error parsing Mapper XML|Unexpected error occurred in scheduled task'; then
  echo "错误：Mapper 加载或额度回收任务异常" >&2
  exit 1
else
  echo "MyBatis Mapper 与额度回收任务无异常"
fi
```

同时保留一次鉴权成功后的额度查询或聊天验收记录，确认数据库 Mapper 能执行真实读写；不要只根据容器处于 `Up` 状态判断部署成功。

下面的命令从终端静默读取 Supabase Access Token，并写入权限为 `600` 的临时 curl 配置。配置通过标准输入传入容器，因此 Token 不会出现在 `docker run` 的命令参数或容器的 `Config.Cmd` 中。请求完成或脚本中断时都会清除临时文件和 shell 变量：

```bash
curl_config="$(mktemp)"
chmod 600 "$curl_config"

cleanup_quota_auth() {
  rm -f "$curl_config"
  unset ACCESS_TOKEN
}
trap cleanup_quota_auth EXIT HUP INT TERM

read -r -s -p 'Supabase Access Token: ' ACCESS_TOKEN
printf '\n'
printf 'header = "Authorization: Bearer %s"\n' "$ACCESS_TOKEN" >"$curl_config"
unset ACCESS_TOKEN

if ! quota_response="$(sudo docker run --rm -i --network deploy_app \
  curlimages/curl:8.10.1 \
  --config - \
  -sS -w '\nSTATUS=%{http_code}\nTYPE=%{content_type}\n' \
  http://drawio-backend:8091/api/v1/quota <"$curl_config")"; then
  echo "错误：内部鉴权额度请求失败" >&2
  exit 1
fi

cleanup_quota_auth
trap - EXIT HUP INT TERM

printf '%s\n' "$quota_response" | grep -Fx 'STATUS=200' >/dev/null \
  || { echo "错误：额度接口没有返回 200" >&2; exit 1; }
printf '%s\n' "$quota_response" | grep -E '^TYPE=application/json' >/dev/null \
  || { echo "错误：额度接口响应不是 JSON" >&2; exit 1; }
printf '%s\n' "$quota_response" | grep -F '"code":"0000"' >/dev/null \
  || { echo "错误：额度接口没有返回成功业务码" >&2; exit 1; }

for field in freeGranted purchasedGranted consumed reserved remaining; do
  printf '%s\n' "$quota_response" | grep -F "\"$field\"" >/dev/null \
    || { echo "错误：额度响应缺少 $field" >&2; exit 1; }
done

echo "内部额度请求通过：STATUS=200，JSON 字段完整"
```

预期为 HTTP 200 JSON，且 `data` 中包含 `freeGranted`、`purchasedGranted`、`consumed`、`reserved` 和 `remaining`。

### 12.6 回滚后端镜像

只有 `drawio-backend:before-production-deploy` 存在时才执行回滚。回滚会使用旧镜像重建后端容器，但不会反向执行 Flyway、删除额度数据、删除卷或删除网络：

```bash
cd /home/ubuntu/drawio-backend

if ! sudo docker image inspect \
  drawio-backend:before-production-deploy >/dev/null 2>&1; then
  echo "错误：回滚镜像不存在，停止回滚" >&2
  exit 1
fi

if ! sudo docker tag \
  drawio-backend:before-production-deploy \
  drawio-backend:latest; then
  echo "错误：无法恢复回滚镜像标签，停止回滚" >&2
  exit 1
fi

if ! sudo docker compose --env-file .env \
  -f docker-compose-production.yml up -d --no-deps --force-recreate backend; then
  echo "错误：回滚容器启动失败" >&2
  exit 1
fi

if ! sudo docker compose --env-file .env \
  -f docker-compose-production.yml ps backend; then
  echo "错误：无法读取回滚后的容器状态" >&2
  exit 1
fi
```

镜像回滚不等于数据库回滚。若新版本已执行 Flyway，必须先确认旧版本与当前 schema 兼容；不要手工删除 Flyway 记录或额度表。
