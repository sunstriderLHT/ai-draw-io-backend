# Supabase 后端鉴权与 AI 次数额度设计

日期：2026-08-11

状态：待用户审核

范围：`draw-io-back-harry` 后端、前端 API 调用适配、云服务器容器编排与 Nginx API 转发

## 1. 背景与目标

当前前端已经使用 Supabase 邮箱和密码完成注册、登录、会话刷新及退出登录，但后端 AI 接口尚未校验 Supabase 身份。现阶段应用主要提供给朋友和面试官体验，需要控制模型成本，同时为后续“拼团购买使用次数”预留可扩展的计费基础。

本期目标：

- 后端只接受合法的 Supabase 登录用户；
- 只有管理员配置的体验白名单用户可以访问 AI 工作台接口；
- 每个白名单用户初始获得 3 次一次性免费 AI 使用额度；
- 只有实际开始产生模型输出的聊天请求消耗额度；
- 额度和消费流水持久化，支持并发安全和请求幂等；
- 后续可以通过订单向同一账户增加购买额度，无需重构鉴权与计数模型；
- 后端 8091 端口继续不暴露到公网，由前端 Nginx 通过同一 Docker 网络转发 `/api/v1`。

本期不包含：

- 拼团、支付和退款业务；
- Google、Apple 或微信登录；
- 管理后台；
- 每日自动重置额度；
- 将 Supabase `service_role` 密钥交给浏览器或后端。

## 2. 已确认的产品规则

1. Supabase 继续开放邮箱和密码注册。
2. 注册成功不等于可以使用 AI；用户还必须位于服务端白名单中。
3. 白名单使用 Supabase 用户 UUID，而不是邮箱：

   ```env
   AI_ALLOWED_USER_IDS=11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222
   ```

4. 每个白名单用户初始总额度为 3 次，不按天重置。
5. `/query_ai_agent_config_list` 和 `/create_session` 不消耗次数。
6. `/chat` 与 `/chat_stream` 每次成功开始模型输出消耗 1 次。
7. 鉴权失败、参数校验失败、额度不足、模型连接失败且没有任何输出时不扣次数。
8. 一旦已经产生第一段有效模型输出，即使用户随后断开连接或取消，也计为 1 次。
9. 未来购买或拼团成功后，通过新增额度流水增加可用次数。

## 3. 方案选择

### 3.1 采用方案

采用“Supabase JWT 本地验证 + UUID 白名单 + Docker MySQL 额度账本”。

- 身份认证：Spring Security OAuth2 Resource Server；
- JWT 公钥：Supabase 项目的 JWKS；
- 用户主键：JWT 的 `sub`；
- 授权：服务端环境变量 UUID 白名单；
- 持久化：项目已有的 MySQL/MyBatis 技术栈；
- 部署：MySQL、后端、前端和 Nginx 位于私有 Docker 网络，仅 Nginx 发布 TCP 80。

### 3.2 未采用方案

- Supabase `/auth/v1/user` 在线校验：每次请求依赖 Supabase 网络，增加延迟和故障面。仅在项目仍使用不适合 JWKS 本地校验的旧式共享密钥时作为临时兼容方案。
- Supabase Postgres：可以实现，但需要额外处理跨云数据库连接、连接池和数据库凭据；当前项目已经具备 MySQL/MyBatis 基础。
- Redis或内存计数：不适合作为购买额度和消费流水的最终事实来源，服务重启和异常恢复也不可靠。

## 4. 总体请求流程

```text
浏览器
  │  Supabase access_token
  │  Authorization: Bearer <token>
  ▼
Nginx :80
  │  /api/v1/*（Docker 私有网络）
  ▼
Spring Security
  │  验证签名、issuer、exp，并读取 sub
  ▼
白名单授权
  │  AI_ALLOWED_USER_IDS 是否包含 sub
  ▼
业务接口
  ├─ 查询配置/创建会话：不扣额度
  └─ chat/chat_stream：额度预占 → 模型调用 → 提交或释放
                         │
                         ▼
                     MySQL 账本
```

前端提交的 `userId` 不再作为身份依据。所有涉及会话隔离、额度、日志关联的用户 ID 都由服务端从 JWT `sub` 注入。即使旧客户端仍携带 `userId`，后端也必须忽略它；后续前端将删除该字段。

## 5. JWT 认证设计

### 5.1 Token 来源

前端通过 Supabase SSR 客户端取得当前会话的 access token，并在每次后端请求中添加：

```http
Authorization: Bearer <supabase-access-token>
```

不得把 refresh token、用户密码、publishable key 以外的 Supabase 密钥传给后端接口。publishable key 仍只用于前端 Supabase SDK，不用于后端信任判断。

### 5.2 后端验证

后端配置：

```env
SUPABASE_ISSUER=https://<project-ref>.supabase.co/auth/v1
SUPABASE_JWKS_URI=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
```

验证项：

- JWT 签名必须能由项目 JWKS 验证；
- `iss` 必须与 `SUPABASE_ISSUER` 完全一致；
- `aud` 必须包含 Supabase 已认证用户使用的 `authenticated`；
- `exp` 未过期；
- `sub` 是合法 UUID；
- `role` 为 `authenticated`；
- 对缺失、畸形、错误签名、错误 issuer、过期 token 统一返回 HTTP 401。

部署前需要在 Supabase 控制台确认项目使用非对称签名密钥。若仍是旧式 HS256 共享密钥，优先按 Supabase 官方迁移流程切换到非对称密钥，而不是把 JWT secret 写入项目文件。

### 5.3 授权范围

所有 `/api/v1/**` 接口都要求：

1. JWT 有效；
2. `sub` 位于 `AI_ALLOWED_USER_IDS`。

不在白名单时返回 HTTP 403 和稳定错误码 `AI_USER_NOT_ALLOWED`。这样未获准体验的注册用户无法枚举智能体配置、创建会话或调用模型。

健康检查使用独立路径，例如 `/actuator/health`，仅在 Docker 私有网络内开放，不放进 `/api/v1/**`。

## 6. 额度模型

### 6.1 账户快照表

```sql
CREATE TABLE ai_user_quota (
    user_id            CHAR(36)     NOT NULL,
    free_granted       INT          NOT NULL DEFAULT 3,
    purchased_granted  INT          NOT NULL DEFAULT 0,
    consumed           INT          NOT NULL DEFAULT 0,
    reserved           INT          NOT NULL DEFAULT 0,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT chk_ai_user_quota_non_negative CHECK (
        free_granted >= 0 AND purchased_granted >= 0 AND consumed >= 0 AND reserved >= 0
    )
);
```

可用次数：

```text
available = free_granted + purchased_granted - consumed - reserved
```

白名单用户第一次访问需要额度的接口时，后端幂等创建账户，`free_granted = 3`。从白名单移除用户不会删除其历史账户或流水。

### 6.2 可审计额度流水表

```sql
CREATE TABLE ai_quota_ledger (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    request_id      VARCHAR(64)  NOT NULL,
    user_id         CHAR(36)     NOT NULL,
    agent_id        VARCHAR(128) NULL,
    endpoint        VARCHAR(32)  NOT NULL,
    entry_type      VARCHAR(32)  NOT NULL,
    amount          INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    source_ref      VARCHAR(128) NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_quota_ledger_user_request (user_id, request_id),
    KEY idx_ai_quota_ledger_user_created (user_id, created_at)
);
```

字段约定：

- `entry_type`：`FREE_GRANT`、`PURCHASE_GRANT`、`CHAT_USAGE`、未来可扩展 `REFUND`；
- `status`：`RESERVED`、`COMMITTED`、`RELEASED`；
- `amount`：正数表示增加，负数表示消费；本期聊天消费为 `-1`；
- `source_ref`：未来保存订单号、拼团号或退款单号；
- `request_id`：由前端每次发送消息时生成并复用，重试不得产生新的扣费流水。

快照表负责高效并发判断，流水表负责审计和未来对账。聊天流水只允许按照 `RESERVED → COMMITTED` 或 `RESERVED → RELEASED` 的状态机更新，不允许删除；额度增减必须保留独立流水。业务只能通过领域服务同时更新两者，禁止控制器直接修改计数。

## 7. 扣费状态机

### 7.1 预占

在调用模型之前开启短事务：

1. 幂等创建额度账户；
2. 根据当前用户的 `user_id + request_id` 查询已有流水：
   - `COMMITTED`：认为该请求已经计费，不重复扣费；
   - `RESERVED`：返回“请求处理中”，避免并发重复调用模型；
   - `RELEASED`：允许同一请求把原流水重新转为 `RESERVED`；
3. 对账户行加锁或执行带条件的原子更新；
4. 若 `available <= 0`，返回 HTTP 402；
5. `reserved += 1` 并写入 `RESERVED` 流水；
6. 提交事务，然后才调用模型，避免在数据库事务中持有长连接。

### 7.2 提交

- 流式接口：收到模型的第一个有效业务输出事件时，以一次性原子操作把流水从 `RESERVED` 改为 `COMMITTED`，同时执行 `reserved -= 1, consumed += 1`。
- 同步接口：成功获得有效模型结果时执行相同提交动作。
- 状态更新必须带 `WHERE status = 'RESERVED'`，保证重复回调只提交一次。

### 7.3 释放

若在首次有效输出前发生连接失败、超时、模型异常或业务主动拒绝，则把流水从 `RESERVED` 改为 `RELEASED`，同时 `reserved -= 1`。释放后不消耗次数。

若客户端在首次输出之后取消，流水已为 `COMMITTED`，不能释放。

为处理进程崩溃留下的预占记录，需要一个轻量恢复任务：服务启动及固定间隔扫描超过配置时限、仍为 `RESERVED` 的记录并释放。默认超时建议 10 分钟。

## 8. API 契约

### 8.1 请求变化

`/chat` 与 `/chat_stream` 新增必填请求头：

```http
Authorization: Bearer <token>
Idempotency-Key: <uuid>
```

服务端不再读取请求体中的 `userId`。`create_session` 同样通过 JWT `sub` 创建该用户的会话。

### 8.2 响应状态

| HTTP | 错误码 | 含义 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 参数或幂等键不合法 |
| 401 | `AUTH_TOKEN_INVALID` | 缺少、无效或过期 token |
| 403 | `AI_USER_NOT_ALLOWED` | 用户不在白名单 |
| 402 | `AI_QUOTA_EXHAUSTED` | 可用次数为 0 |
| 409 | `AI_REQUEST_IN_PROGRESS` | 相同幂等键仍在处理中 |
| 500/502 | 现有服务错误码 | 模型或内部服务失败 |

额度不足响应至少包含：

```json
{
  "code": "AI_QUOTA_EXHAUSTED",
  "info": "体验次数已用完",
  "data": {
    "remaining": 0
  }
}
```

成功的聊天响应和流结束事件应包含 `remaining`，让前端刷新剩余次数。本期可额外提供只读接口 `GET /api/v1/quota`，供工作台首次加载展示额度；该接口不扣次数。

### 8.3 SSE 错误

HTTP 响应头尚未发送时，使用正常 HTTP 状态返回鉴权、白名单和额度错误。流已经建立后发生模型错误，则发送结构化 SSE `error` 事件并关闭连接，不再尝试改变已提交的消费记录。

## 9. 代码职责划分

- `trigger`：Security 配置、JWT 到认证上下文的转换、HTTP 状态和 DTO；
- `domain`：白名单策略、额度领域服务、预占/提交/释放规则；
- `infrastructure`：MyBatis DAO、MySQL 事务实现、流水恢复查询；
- `app`：环境配置、组件装配和定时任务启用。

Controller 只负责提取已认证用户和调用领域服务。模型调用逻辑通过一个计费编排器包装，避免 `/chat` 和 `/chat_stream` 分别实现一套扣费规则。

## 10. 前端适配

前端 API 客户端负责：

- 从 Supabase 当前会话取得 access token；
- 统一添加 `Authorization` 请求头；
- 每次用户发送消息生成 `Idempotency-Key`；网络重试复用同一个键；
- 不再生成或发送业务 `userId`；
- 对 401 引导重新登录，对 403 显示“暂未开通体验”，对 402 显示“3 次体验已用完”；
- 展示后端返回的剩余次数；
- 不在浏览器自行扣减或信任本地次数。

## 11. 部署与配置

生产环境变量只保存在云服务器的权限受限文件中，不提交 Git：

```env
SUPABASE_ISSUER=https://<project-ref>.supabase.co/auth/v1
SUPABASE_JWKS_URI=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
AI_ALLOWED_USER_IDS=<uuid-1>,<uuid-2>
AI_FREE_QUOTA=3
AI_RESERVATION_TIMEOUT=PT10M
MYSQL_DATABASE=drawio
MYSQL_USER=drawio_app
MYSQL_PASSWORD=<generated-password>
MYSQL_ROOT_PASSWORD=<different-generated-password>
```

安全边界：

- 公网仅开放 TCP 80 和 SSH 22；
- 8091 和 3306 不发布到宿主机公网；
- Nginx 将 `/api/v1/` 转发到 Docker 网络中的后端服务；
- 代理保留 `Authorization`、`Idempotency-Key`、SSE 关闭缓冲和长超时配置；
- MySQL 使用命名卷持久化，并纳入备份；
- 日志不得输出 Authorization、完整 JWT、用户消息正文、数据库密码或模型密钥；
- 现有模型 API 密钥迁移到服务器环境变量或 Docker secret，不继续保存在 YAML 或镜像层中。

## 12. 测试策略

### 12.1 鉴权

- 合法 token 通过；
- 缺少、过期、伪造签名、错误 issuer、无效 `sub` 返回 401；
- 非白名单用户返回 403；
- 客户端伪造请求体 `userId` 无法访问另一用户会话；
- 认证上下文中的用户 UUID 始终覆盖客户端字段。

### 12.2 额度

- 新白名单用户恰好获得 3 次；
- 查询智能体、查询额度和创建会话不扣次数；
- 第 3 次成功，第 4 次返回 402；
- 模型首次输出前失败会释放预占；
- 首次输出后取消仍然扣 1 次；
- 相同幂等键不会重复扣费或重复启动模型；
- 同一用户并发 4 个请求时最多 3 个能够预占；
- 重复提交/释放回调保持幂等；
- 超时预占能够被恢复任务释放；
- 未来购买流水能够增加 `purchased_granted`，历史消费不变。

### 12.3 集成与部署

- 使用测试 MySQL 验证真实事务和并发，而不只模拟 DAO；
- 前后端集成验证 access token 转发和 SSE；
- Compose 渲染结果只能发布 Nginx TCP 80；
- 公网 `/api/v1/query_ai_agent_config_list` 无 token 返回 401；
- 合法白名单 token 可以查询配置；
- 公网无法连接 8091 和 3306。

## 13. 分阶段上线

1. 后端加入 JWT 验证、白名单和统一错误响应，Nginx 仍保持 API 404。
2. 加入 MySQL 表、额度服务和完整测试。
3. 前端加入 Bearer token、幂等键和额度错误展示。
4. 在 Supabase 控制台取得体验用户 UUID，写入服务器白名单。
5. 同一 Docker 网络启动 MySQL 与后端，先从服务器内部验证。
6. 修改 Nginx 开放 `/api/v1` 反向代理并重新加载。
7. 用一个白名单账号和一个非白名单账号完成验收。
8. 观察日志和额度流水后，再邀请朋友或面试官体验。

回滚时只需恢复 Nginx 对 `/api/v1` 的 404，即可立即停止外部模型消耗；数据库卷保留，不丢失额度记录。

## 14. 验收标准

- 未登录、token 无效和非白名单用户无法触发任何模型调用；
- 用户身份只来自验证后的 JWT `sub`；
- 每个白名单用户初始总额度严格为 3；
- 只有产生第一段有效模型输出的聊天消耗一次；
- 并发、重试、断流和服务重启不会造成超卖或重复扣费；
- 额度流水可以解释每一次增加、预占、提交和释放；
- 8091、3306 仍不对公网开放；
- 秘钥不进入 Git、前端包、Docker build context 或应用日志；
- 后续拼团订单可以仅通过新增购买额度流水接入。

## 15. 参考资料

- Supabase 官方 JWT 说明：<https://supabase.com/docs/guides/auth/jwts>
- Supabase 官方签名密钥与迁移说明：<https://supabase.com/docs/guides/auth/signing-keys>
