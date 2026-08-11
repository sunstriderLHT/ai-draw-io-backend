# Supabase Backend Auth and AI Quota Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Spring Boot 后端只接受 Supabase 白名单用户，并以 MySQL 事务安全地发放和消费每人 3 次 AI 体验额度。

**Architecture:** Spring Security Resource Server 使用 Supabase JWKS 本地验证 access token，并从 JWT `sub` 提供唯一可信身份。额度领域服务通过 MyBatis/MySQL 维护账户快照和审计流水，聊天编排器在模型调用前预占额度、首次有效输出时提交、首次输出前失败时释放。

**Tech Stack:** Java 17、Spring Boot 3.4.3、Spring Security OAuth2 Resource Server、MyBatis 3.0.4、MySQL 8、Flyway、RxJava 3、JUnit 4、MockMvc、Testcontainers MySQL

## Global Constraints

- 所有 `/api/v1/**` 必须验证 Supabase JWT，用户身份只允许来自 JWT `sub`。
- 只有 `AI_ALLOWED_USER_IDS` 中的 Supabase UUID 可以访问 `/api/v1/**`。
- 每个白名单用户初始总额度严格为 3，不按天重置。
- 查询智能体、创建会话和查询额度不扣次数。
- `/chat` 和 `/chat_stream` 只有产生第一段非空模型输出时才消费 1 次。
- 首次输出前失败或取消释放预占；首次输出后取消仍计费。
- 同一用户与同一 `Idempotency-Key` 不得重复调用模型或重复扣费。
- 本计划不开放公网 API；TCP 8091、3306 不得发布到公网。
- 不得把真实 Supabase 私钥、数据库密码或模型密钥提交到 Git。
- 本计划只实施后端；前端 token 适配和 Nginx 转发使用后续独立计划。

## File Map

- `trigger/security/*`：JWT 验证、白名单、401/403 和可信用户身份。
- `domain/quota/*`：额度状态机、模型、异常和持久化端口。
- `infrastructure/dao/*`、`infrastructure/adapter/repository/*`：MyBatis 映射和 MySQL 短事务。
- `app/resources/db/migration/*`：Flyway 表结构。
- `trigger/http/MeteredAgentChatFacade.java`：同步与流式计费编排。
- `trigger/http/AgentServiceController.java`：HTTP 入口、幂等键和响应映射。
- `trigger/job/QuotaReservationRecoveryJob.java`：释放崩溃遗留的超时预占。

---

### Task 1: Establish a Reproducible Test Baseline

**目的：** 先证明现有项目能离线编译和运行快速测试，再引入安全与数据库依赖。

**为什么：** 仓库里部分测试会调用真实模型；若直接跑全量测试，不但慢，还可能消耗 token。后续 TDD 默认只跑明确列出的离线测试。

**Files:**
- Modify: `draw-io-front-harry-trigger/pom.xml`
- Modify: `draw-io-front-harry-app/pom.xml`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/DependencySmokeTest.java`

**Interfaces:**
- Produces: Resource Server、Flyway、Security Test 和 Testcontainers 类型可用。

- [ ] **Step 1: 记录基线**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -DskipTests package
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=AgentStreamSubscriptionTest,AgentOutputEventMapperTest test
```

Expected: 两条命令退出 0，且不访问模型 API。

- [ ] **Step 2: 写依赖冒烟测试并确认 RED**

```java
public class DependencySmokeTest {
    @Test
    public void shouldLoadSecurityAndDatabaseTestTypes() {
        assertNotNull(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        assertNotNull(org.testcontainers.containers.MySQLContainer.class);
    }
}
```

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=DependencySmokeTest test
```

Expected: FAIL，提示依赖类型不存在。

- [ ] **Step 3: 添加依赖**

Trigger POM:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

App POM:

```xml
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>
<dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><scope>test</scope></dependency>
```

- [ ] **Step 4: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=DependencySmokeTest test
git add draw-io-front-harry-trigger/pom.xml draw-io-front-harry-app/pom.xml draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/DependencySmokeTest.java
git commit -m "build: add auth and quota dependencies"
```

---

### Task 2: Validate JWTs and Enforce the UUID Allowlist

**目的：** 建立后端第一道安全边界，合法登录且被邀请的用户才能进入 API。

**为什么：** 前端显示已登录不构成后端信任；后端必须独立验证签名、issuer、audience、过期时间、角色和 UUID 白名单。

**Files:**
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security/SupabaseAuthProperties.java`
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security/SupabaseSecurityConfig.java`
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security/JsonAuthenticationEntryPoint.java`
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security/JsonAccessDeniedHandler.java`
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security/SupabaseClaimsValidator.java`
- Modify: `draw-io-front-harry-app/src/main/resources/application.yml`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/SupabaseSecurityTest.java`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/SupabaseClaimsValidatorTest.java`

**Interfaces:**
- Produces: `SupabaseAuthProperties`、`JwtDecoder`、`SecurityFilterChain`。

- [ ] **Step 1: 写 401、403、200 测试**

```java
mockMvc.perform(get("/api/v1/query_ai_agent_config_list"))
    .andExpect(status().isUnauthorized())
    .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

mockMvc.perform(get("/api/v1/query_ai_agent_config_list")
    .with(jwt().jwt(jwt -> jwt.subject(DISALLOWED_UUID).claim("role", "authenticated"))))
    .andExpect(status().isForbidden())
    .andExpect(jsonPath("$.code").value("AI_USER_NOT_ALLOWED"));
```

Allowed UUID case must return 200.

Test the custom claim validator directly with constructed `Jwt` values: correct issuer/audience/role succeeds; wrong issuer, missing `authenticated` audience, wrong role and expired token each fail. MockMvc's `jwt()` helper bypasses signature decoding, so this separate test is required to cover claim validation.

Each Spring security test supplies explicit test-only properties such as `SUPABASE_ISSUER=https://test-project.invalid/auth/v1` and `SUPABASE_JWKS_URI=http://127.0.0.1:9/jwks`. Production configuration has no real-value defaults and therefore fails fast when required environment variables are absent.

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=SupabaseSecurityTest,SupabaseClaimsValidatorTest test
```

- [ ] **Step 3: 实现类型化配置**

```java
@ConfigurationProperties(prefix = "supabase.auth")
public record SupabaseAuthProperties(URI issuer, URI jwksUri, Set<UUID> allowedUserIds) {
    public SupabaseAuthProperties {
        allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
    }
}
```

```yaml
supabase:
  auth:
    issuer: ${SUPABASE_ISSUER}
    jwks-uri: ${SUPABASE_JWKS_URI}
    allowed-user-ids: ${AI_ALLOWED_USER_IDS:}
```

- [ ] **Step 4: 实现验证和授权链**

Use `NimbusJwtDecoder.withJwkSetUri(...)`. Compose `JwtValidators.createDefaultWithIssuer(...)` with `SupabaseClaimsValidator`, which requires audience `authenticated` and claim `role=authenticated`.

```java
http.csrf(AbstractHttpConfigurer::disable)
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(a -> a
        .requestMatchers("/api/v1/**").access(allowedSupabaseUser(properties))
        .anyRequest().permitAll())
    .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
        .authenticationEntryPoint(authenticationEntryPoint))
    .exceptionHandling(e -> e.accessDeniedHandler(accessDeniedHandler));
```

`allowedSupabaseUser` parses `authentication.getName()` as UUID and checks `allowedUserIds`.

- [ ] **Step 5: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=SupabaseSecurityTest,SupabaseClaimsValidatorTest test
git add draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security draw-io-front-harry-app/src/main/resources/application.yml draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/SupabaseSecurityTest.java draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/SupabaseClaimsValidatorTest.java
git commit -m "feat: protect AI APIs with Supabase JWT"
```

---

### Task 3: Remove Client-Controlled User Identity

**目的：** 会话归属只由验证后的 JWT 决定，关闭请求体冒充用户的入口。

**为什么：** 当前 Controller 直接使用 `requestDTO.getUserId()`；即使 JWT 有效，登录用户仍能提交别人的 ID。

**Files:**
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security/AuthenticatedUserProvider.java`
- Modify: `draw-io-front-harry-api/src/main/java/cn/bugstack/ai/api/dto/ChatRequestDTO.java`
- Modify: `draw-io-front-harry-api/src/main/java/cn/bugstack/ai/api/dto/CreateSessionRequestDTO.java`
- Modify: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/TrustedUserIdentityTest.java`

**Interfaces:**
- Produces: `String AuthenticatedUserProvider.requireUserId()`.

- [ ] **Step 1: 写身份伪造回归测试**

```java
mockMvc.perform(post("/api/v1/create_session")
        .with(jwt().jwt(jwt -> jwt.subject(ALLOWED_UUID).claim("role", "authenticated")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"agentId\":\"100001\",\"userId\":\"attacker\"}"))
    .andExpect(status().isOk());

verify(chatService).createSession("100001", ALLOWED_UUID);
verify(chatService, never()).createSession("100001", "attacker");
```

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=TrustedUserIdentityTest test
```

- [ ] **Step 3: 实现可信身份提供器**

```java
@Component
public class AuthenticatedUserProvider {
    public String requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InsufficientAuthenticationException("Supabase user is not authenticated");
        }
        return UUID.fromString(authentication.getName()).toString();
    }
}
```

Delete `userId` fields from both request DTOs. Controller calls `requireUserId()` once per request and passes that UUID to all `ChatService` methods. Remove user message content from logs.

- [ ] **Step 4: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=TrustedUserIdentityTest test
git add draw-io-front-harry-api/src/main/java/cn/bugstack/ai/api/dto draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/security/AuthenticatedUserProvider.java draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/TrustedUserIdentityTest.java
git commit -m "fix: derive chat identity from verified JWT"
```

---

### Task 4: Define the Quota Domain State Machine

**目的：** 先用纯 Java 固定额度规则，再连接数据库。

**为什么：** 预占、提交、释放是成本控制核心。把它们做成领域接口，可以快速单测并防止规则散落在 Controller 和 SQL。

**Files:**
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/model/QuotaSnapshot.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/model/QuotaReservation.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/model/QuotaLedgerStatus.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/adapter/repository/IAiQuotaRepository.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/service/IAiQuotaService.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/service/AiQuotaService.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/exception/QuotaExhaustedException.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/exception/QuotaRequestInProgressException.java`
- Create: `draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota/exception/QuotaRequestAlreadyCompletedException.java`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/domain/quota/AiQuotaServiceTest.java`

**Interfaces:**

```java
public interface IAiQuotaService {
    QuotaReservation reserve(String userId, String requestId, String agentId, String endpoint);
    QuotaSnapshot commit(String userId, String requestId);
    QuotaSnapshot release(String userId, String requestId);
    QuotaSnapshot getSnapshot(String userId);
    int releaseExpired(Instant cutoff);
}

public interface IAiQuotaRepository {
    QuotaReservation reserve(String userId, String requestId, String agentId, String endpoint, int freeQuota);
    QuotaSnapshot commit(String userId, String requestId);
    QuotaSnapshot release(String userId, String requestId);
    QuotaSnapshot findOrCreate(String userId, int freeQuota);
    int releaseExpired(Instant cutoff);
}
```

- [ ] **Step 1: 写纯单元测试**

Use a mocked repository and cover:

```java
@Test(expected = IllegalArgumentException.class)
public void shouldRejectNonUuidUserId() {
    service.reserve("not-a-uuid", UUID.randomUUID().toString(), "100001", "chat");
}

@Test
public void shouldDelegateWithThreeFreeUses() {
    service.reserve(USER_ID, REQUEST_ID, "100001", "chat_stream");
    verify(repository).reserve(USER_ID, REQUEST_ID, "100001", "chat_stream", 3);
}
```

Also reject a non-UUID idempotency key and an endpoint other than `chat` or `chat_stream`.
Repository results for an existing `RESERVED` request map to `QuotaRequestInProgressException`; an existing `COMMITTED` request maps to `QuotaRequestAlreadyCompletedException`. Both cases must stop before model invocation.

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=AiQuotaServiceTest test
```

- [ ] **Step 3: 实现最小领域模型**

```java
public record QuotaSnapshot(int freeGranted, int purchasedGranted, int consumed, int reserved) {
    public int remaining() {
        return freeGranted + purchasedGranted - consumed - reserved;
    }
}

public record QuotaReservation(String requestId, QuotaLedgerStatus status, QuotaSnapshot snapshot) {}
public enum QuotaLedgerStatus { RESERVED, COMMITTED, RELEASED }
```

`AiQuotaService` validates both UUIDs with `UUID.fromString`, restricts endpoint values, reads `@Value("${ai.quota.free:3}")`, and delegates to the repository.
`QuotaExhaustedException` carries the locked `QuotaSnapshot`, so the HTTP layer can return an accurate `remaining=0` without querying again outside the failed reservation transaction.

- [ ] **Step 4: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=AiQuotaServiceTest test
git add draw-io-front-harry-domain/src/main/java/cn/bugstack/ai/domain/quota draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/domain/quota/AiQuotaServiceTest.java
git commit -m "feat: define AI quota state machine"
```

---

### Task 5: Persist Quotas Transactionally in MySQL

**目的：** 让额度在重启后仍存在，并保证同一用户的并发请求不会超卖。

**为什么：** 内存计数不能支持购买和审计；Java 中“先查询再更新”也会产生竞态，必须由 InnoDB 行锁和单事务保护账户与流水。

**Files:**
- Create: `draw-io-front-harry-app/src/main/resources/db/migration/V1__create_ai_quota_tables.sql`
- Create: `draw-io-front-harry-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/IAiQuotaDao.java`
- Create: `draw-io-front-harry-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AiUserQuotaPO.java`
- Create: `draw-io-front-harry-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/dao/po/AiQuotaLedgerPO.java`
- Create: `draw-io-front-harry-infrastructure/src/main/java/cn/bugstack/ai/infrastructure/adapter/repository/MySqlAiQuotaRepository.java`
- Modify: `draw-io-front-harry-app/src/main/resources/application-dev.yml`
- Modify: `draw-io-front-harry-app/src/main/resources/application-prod.yml`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/infrastructure/quota/MySqlAiQuotaRepositoryTest.java`

**Interfaces:**
- Implements: `IAiQuotaRepository` from Task 4.

- [ ] **Step 1: 写 Flyway migration**

Create the approved `ai_user_quota` and `ai_quota_ledger` tables. Mandatory details:

```sql
PRIMARY KEY (user_id)
```

```sql
UNIQUE KEY uk_ai_quota_ledger_user_request (user_id, request_id),
KEY idx_ai_quota_ledger_status_updated (status, updated_at)
```

Both tables use `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`; migration must not contain `DROP TABLE`.

- [ ] **Step 2: 写真实 MySQL 集成测试**

```java
@ClassRule
public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("drawio_test")
        .withUsername("drawio")
        .withPassword("test-only-password");

@DynamicPropertySource
public static void mysqlProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
}
```

Tests must prove:

- new account is `3 granted, 0 consumed, 0 reserved`;
- first three distinct requests reserve and the fourth throws `QuotaExhaustedException`;
- commit moves `reserved 1 → 0`, `consumed 0 → 1` exactly once;
- release moves `reserved 1 → 0` without consuming;
- duplicate committed request does not change counters;
- duplicate committed request throws `QuotaRequestAlreadyCompletedException` and cannot enter a model call;
- four concurrent reservations produce exactly three successes.

- [ ] **Step 3: 运行并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=MySqlAiQuotaRepositoryTest test
```

Expected: FAIL because migration and repository are absent. Docker must be running.

- [ ] **Step 4: 实现固定锁顺序**

Every mutating method is `@Transactional` and always performs:

1. `INSERT IGNORE` account; if inserted, add one committed `FREE_GRANT` ledger;
2. `SELECT account ... FOR UPDATE`;
3. `SELECT ledger ... FOR UPDATE`;
4. validate current state and available count;
5. update snapshot and ledger in the same transaction.

```java
int available = account.getFreeGranted()
        + account.getPurchasedGranted()
        - account.getConsumed()
        - account.getReserved();
if (available <= 0) throw new QuotaExhaustedException();
```

All transitions include the previous status:

```sql
UPDATE ai_quota_ledger
SET status = 'COMMITTED', updated_at = NOW(3)
WHERE user_id = #{userId} AND request_id = #{requestId} AND status = 'RESERVED'
```

- [ ] **Step 5: 配置数据源但不写真实密码**

```yaml
spring:
  datasource:
    url: ${MYSQL_URL:jdbc:mysql://127.0.0.1:3306/drawio?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false}
    username: ${MYSQL_USER:drawio_app}
    password: ${MYSQL_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration
```

- [ ] **Step 6: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=MySqlAiQuotaRepositoryTest test
git add draw-io-front-harry-app/src/main/resources/db/migration draw-io-front-harry-app/src/main/resources/application-dev.yml draw-io-front-harry-app/src/main/resources/application-prod.yml draw-io-front-harry-infrastructure/src/main/java/cn/bugstack/ai/infrastructure draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/infrastructure/quota/MySqlAiQuotaRepositoryTest.java
git commit -m "feat: persist AI quotas in MySQL"
```

---

### Task 6: Meter Synchronous Chat Exactly Once

**目的：** `/chat` 成功返回有效模型内容时扣一次，模型异常或空结果不扣。

**为什么：** 独立计费门面可以隔离模型调用与额度事务，并能用 mock 测试，不产生真实模型费用。

**Files:**
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/MeteredAgentChatFacade.java`
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/MeteredChatResult.java`
- Modify: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java`
- Modify: `draw-io-front-harry-api/src/main/java/cn/bugstack/ai/api/dto/ChatResponseDTO.java`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/MeteredSynchronousChatTest.java`

**Interfaces:**

```java
public record MeteredChatResult(AgentChatResultVO result, int remaining) {}

public MeteredChatResult chat(
        String userId, String requestId, String agentId, String sessionId, String message);
```

- [ ] **Step 1: 写调用顺序和失败释放测试**

```java
InOrder order = inOrder(quotaService, chatService);
order.verify(quotaService).reserve(USER_ID, REQUEST_ID, AGENT_ID, "chat");
order.verify(chatService).handleMessage(AGENT_ID, USER_ID, SESSION_ID, "hello");
order.verify(quotaService).commit(USER_ID, REQUEST_ID);
```

When `handleMessage` throws, verify one release and zero commits. Empty content also releases once and raises `AI_EMPTY_RESPONSE`.

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=MeteredSynchronousChatTest test
```

- [ ] **Step 3: 实现同步编排**

```java
quotaService.reserve(userId, requestId, agentId, "chat");
boolean committed = false;
try {
    AgentChatResultVO result = chatService.handleMessage(agentId, userId, sessionId, message);
    if (result == null || result.getContent() == null || result.getContent().isBlank()) {
        throw new AppException("AI_EMPTY_RESPONSE", "模型没有返回有效内容");
    }
    QuotaSnapshot snapshot = quotaService.commit(userId, requestId);
    committed = true;
    return new MeteredChatResult(result, snapshot.remaining());
} finally {
    if (!committed) quotaService.release(userId, requestId);
}
```

The facade creates a session using the trusted user when `sessionId` is blank. `ChatResponseDTO` gains `remaining`.

- [ ] **Step 4: Controller 要求幂等键**

```java
public Response<ChatResponseDTO> chat(
        @RequestHeader("Idempotency-Key") String requestId,
        @RequestBody ChatRequestDTO requestDTO) {
    String userId = authenticatedUserProvider.requireUserId();
    MeteredChatResult result = meteredAgentChatFacade.chat(
            userId, requestId, requestDTO.getAgentId(), requestDTO.getSessionId(), requestDTO.getMessage());
    // map content, traces and remaining into ChatResponseDTO
}
```

- [ ] **Step 5: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=MeteredSynchronousChatTest test
git add draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http draw-io-front-harry-api/src/main/java/cn/bugstack/ai/api/dto/ChatResponseDTO.java draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/MeteredSynchronousChatTest.java
git commit -m "feat: meter synchronous AI chat"
```

---

### Task 7: Meter Streaming Chat on the First Effective Event

**目的：** `/chat_stream` 在首个非空模型事件进入发送链前提交消费，并正确处理错误与取消。

**为什么：** 提交过早会让连接失败也扣费；提交过晚会让用户收到内容后断开却不扣费。

**Files:**
- Modify: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/MeteredAgentChatFacade.java`
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/MeteredAgentOutput.java`
- Modify: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/MeteredStreamingChatTest.java`

**Interfaces:**

```java
public record MeteredAgentOutput(AgentOutputEventVO output, Integer remaining) {}

public Flowable<MeteredAgentOutput> chatStream(
        String userId, String requestId, String agentId, String sessionId, String message);
```

- [ ] **Step 1: 写 RxJava 生命周期测试**

Cover:

- two output items → one commit;
- upstream error before output → one release, zero commits;
- cancel before output → one release;
- cancel after first nonblank output → one commit, zero releases;
- blank output does not commit.

```java
facade.chatStream(USER_ID, REQUEST_ID, AGENT_ID, SESSION_ID, "hello")
        .test().assertValueCount(2).assertComplete();
verify(quotaService, times(1)).commit(USER_ID, REQUEST_ID);
```

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=MeteredStreamingChatTest test
```

- [ ] **Step 3: 实现订阅时预占和一次性转换**

```java
return Flowable.defer(() -> {
    quotaService.reserve(userId, requestId, agentId, "chat_stream");
    AtomicBoolean committed = new AtomicBoolean(false);
    AtomicBoolean released = new AtomicBoolean(false);
    return chatService.handleMessageStream(agentId, userId, sessionId, message)
        .map(output -> {
            Integer remaining = null;
            if (isEffective(output) && committed.compareAndSet(false, true)) {
                remaining = quotaService.commit(userId, requestId).remaining();
            }
            return new MeteredAgentOutput(output, remaining);
        })
        .doOnError(e -> releaseBeforeOutput(userId, requestId, committed, released))
        .doOnCancel(() -> releaseBeforeOutput(userId, requestId, committed, released))
        .doOnComplete(() -> releaseBeforeOutput(userId, requestId, committed, released));
});
```

`isEffective` requires non-null, nonblank content. `releaseBeforeOutput` uses `compareAndSet` so it runs once.

- [ ] **Step 4: Controller 传递幂等键和 remaining**

`/chat_stream` accepts `@RequestHeader("Idempotency-Key")`. SSE data preserves `type`, `agentName`, `content`, `completed`, and nullable `remaining`. Commit happens before `emitter.send`, matching the confirmed billing rule.

- [ ] **Step 5: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=MeteredStreamingChatTest,AgentStreamSubscriptionTest test
git add draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/MeteredStreamingChatTest.java
git commit -m "feat: meter streaming AI chat"
```

---

### Task 8: Return Correct HTTP Errors and Expose Quota

**目的：** 让浏览器区分重新登录、无体验权限、次数用完和请求处理中。

**为什么：** 当前 Controller 捕获异常后仍返回 HTTP 200，代理、监控和前端无法做正确决策。

**Files:**
- Create: `draw-io-front-harry-api/src/main/java/cn/bugstack/ai/api/dto/QuotaResponseDTO.java`
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/ApiExceptionHandler.java`
- Modify: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http/AgentServiceController.java`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/AiApiContractTest.java`

**Interfaces:**
- Produces: `GET /api/v1/quota`, HTTP 400/402/409 and stable JSON codes.

- [ ] **Step 1: 写 API 契约测试**

```java
mockMvc.perform(get("/api/v1/quota").with(allowedJwt()))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.remaining").value(3));

mockMvc.perform(post("/api/v1/chat")
        .with(allowedJwt())
        .header("Idempotency-Key", UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON)
        .content(validChatJson()))
    .andExpect(status().isPaymentRequired())
    .andExpect(jsonPath("$.code").value("AI_QUOTA_EXHAUSTED"));
```

Also assert malformed idempotency key is 400, an already-reserved request is 409 `AI_REQUEST_IN_PROGRESS`, and an already-committed request is 409 `AI_REQUEST_ALREADY_COMPLETED`.

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=AiApiContractTest test
```

- [ ] **Step 3: 实现 DTO、只读接口和异常映射**

```java
public record QuotaResponseDTO(
        int freeGranted, int purchasedGranted, int consumed, int reserved, int remaining) {
    public static QuotaResponseDTO from(QuotaSnapshot s) {
        return new QuotaResponseDTO(s.freeGranted(), s.purchasedGranted(),
                s.consumed(), s.reserved(), s.remaining());
    }
}
```

```java
@ExceptionHandler(QuotaExhaustedException.class)
public ResponseEntity<Response<QuotaResponseDTO>> quotaExhausted(QuotaExhaustedException error) {
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
        .body(Response.<QuotaResponseDTO>builder()
            .code("AI_QUOTA_EXHAUSTED").info("体验次数已用完")
            .data(QuotaResponseDTO.from(error.getSnapshot())).build());
}
```

Add explicit 400 handlers and separate 409 handlers for `AI_REQUEST_IN_PROGRESS` and `AI_REQUEST_ALREADY_COMPLETED`. Remove broad controller catches that turn errors into successful HTTP responses. Unexpected errors are logged once without token or message content.

- [ ] **Step 4: 确认 GREEN 并提交**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=AiApiContractTest,SupabaseSecurityTest,TrustedUserIdentityTest test
git add draw-io-front-harry-api/src/main/java/cn/bugstack/ai/api/dto/QuotaResponseDTO.java draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/http draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/AiApiContractTest.java
git commit -m "feat: expose quota API contract"
```

---

### Task 9: Recover Stale Reservations and Run the Backend Gate

**目的：** 释放进程崩溃遗留的预占，并完成不调用真实模型的后端总验收。

**为什么：** 没有恢复任务时，预占后崩溃会永久占住次数；没有总验收则不能安全进入前端联调。

**Files:**
- Create: `draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/job/QuotaReservationRecoveryJob.java`
- Modify: `draw-io-front-harry-app/src/main/java/cn/bugstack/ai/Application.java`
- Modify: `draw-io-front-harry-app/src/main/java/cn/bugstack/ai/config/AiAgentAutoConfig.java`
- Modify: `draw-io-front-harry-app/src/main/resources/application.yml`
- Modify: `draw-io-front-harry-app/src/main/resources/agent/agent-draw-io.yml`
- Modify: `draw-io-front-harry-app/src/main/resources/agent/demo.yml`
- Modify: `draw-io-front-harry-app/src/main/resources/agent/draw-io-agent.yml`
- Modify: `draw-io-front-harry-app/src/main/resources/agent/only-one-agent.yml`
- Modify: `draw-io-front-harry-app/src/main/resources/agent/parallel_research_app.yml`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/QuotaReservationRecoveryJobTest.java`
- Test: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/AgentConfigSecretSafetyTest.java`
- Create: `docs/dev-ops/backend-auth-quota-runbook.md`

**Interfaces:**
- Consumes: `IAiQuotaService.releaseExpired(Instant cutoff)`.

- [ ] **Step 1: 写密钥安全回归测试**

The test reads all packaged `agent/*.yml` resources and asserts every `api-key` value starts with `${` rather than a literal. It also reads `AiAgentAutoConfig.java` and rejects serialization of the complete configuration object in log statements.

```java
assertFalse(yamlText.matches("(?s).*api-key\\s*:\\s*(?!\\$\\{).+"));
assertFalse(autoConfigSource.contains("JSON.toJSONString(aiAgentAutoConfigProperties"));
```

- [ ] **Step 2: 运行密钥测试并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=AgentConfigSecretSafetyTest test
```

Expected: FAIL because current YAML contains literal model keys and startup logging serializes the complete configuration.

- [ ] **Step 3: 外置模型密钥并停止记录配置全文**

Replace every packaged key value with an environment reference:

```yaml
api-key: ${AI_MODEL_API_KEY}
```

Change startup logs to emit only non-sensitive agent identifiers and counts. Never log `AiAgentAutoConfigProperties` as JSON. The runbook must instruct the user to revoke and rotate previously committed model keys in the model provider console before deployment.

- [ ] **Step 4: 确认密钥测试 GREEN**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=AgentConfigSecretSafetyTest test
```

- [ ] **Step 5: 写固定时钟恢复测试**

```java
@Test
public void shouldReleaseReservationsOlderThanConfiguredTimeout() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-11T00:20:00Z"), ZoneOffset.UTC);
    QuotaReservationRecoveryJob job =
            new QuotaReservationRecoveryJob(quotaService, clock, Duration.ofMinutes(10));
    job.releaseExpiredReservations();
    verify(quotaService).releaseExpired(Instant.parse("2026-08-11T00:10:00Z"));
}
```

- [ ] **Step 6: 运行恢复测试并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -Dtest=QuotaReservationRecoveryJobTest test
```

- [ ] **Step 7: 实现定时恢复**

Enable scheduling on `Application` and implement:

```java
@Scheduled(fixedDelayString = "${ai.quota.recovery-interval:PT1M}")
public void releaseExpiredReservations() {
    quotaService.releaseExpired(clock.instant().minus(reservationTimeout));
}
```

```yaml
ai:
  quota:
    free: ${AI_FREE_QUOTA:3}
    reservation-timeout: ${AI_RESERVATION_TIMEOUT:PT10M}
    recovery-interval: ${AI_RESERVATION_RECOVERY_INTERVAL:PT1M}
```

- [ ] **Step 8: 写运维手册**

Document exact environment variable names, database initialization, whitelist UUID lookup, quota inspection SQL, and rollback by leaving Nginx `/api/v1` at 404. Use example UUIDs only and no literal credentials.

- [ ] **Step 9: 运行完整离线门禁**

```powershell
$env:AI_MODEL_API_KEY='test-only-placeholder'
mvn.cmd -pl draw-io-front-harry-app -am -Dtest=DependencySmokeTest,SupabaseSecurityTest,SupabaseClaimsValidatorTest,TrustedUserIdentityTest,AiQuotaServiceTest,MySqlAiQuotaRepositoryTest,MeteredSynchronousChatTest,MeteredStreamingChatTest,AiApiContractTest,AgentConfigSecretSafetyTest,QuotaReservationRecoveryJobTest,AgentStreamSubscriptionTest,AgentOutputEventMapperTest test
mvn.cmd -pl draw-io-front-harry-app -am -DskipTests package
git diff --check
```

Expected: all named tests pass, package succeeds, and no real model call occurs.

- [ ] **Step 10: 扫描敏感信息并提交**

```powershell
rg --pcre2 -n "service_role|sb_secret_|JWT_SECRET|api-key\s*:\s*(?!\$\{)|MYSQL_PASSWORD\s*[:=]\s*[^$<]" . -g "!target/**" -g "!.git/**"
git add draw-io-front-harry-trigger/src/main/java/cn/bugstack/ai/trigger/job/QuotaReservationRecoveryJob.java draw-io-front-harry-app/src/main/java/cn/bugstack/ai/Application.java draw-io-front-harry-app/src/main/java/cn/bugstack/ai/config/AiAgentAutoConfig.java draw-io-front-harry-app/src/main/resources/application.yml draw-io-front-harry-app/src/main/resources/agent draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/trigger/QuotaReservationRecoveryJobTest.java draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/security/AgentConfigSecretSafetyTest.java docs/dev-ops/backend-auth-quota-runbook.md
git commit -m "feat: recover stale AI quota reservations"
```

Expected: scan contains only documentation warnings or test fixtures, not real secrets.

---

## Guided Execution Protocol

执行每个 Task 时，指导顺序固定为：

1. **本步目的**：说明解决的风险；
2. **为什么这样做**：解释技术选择及取舍；
3. **先写测试**：展示预期并运行 RED；
4. **最小实现**：只实现当前行为；
5. **验证结果**：给出命令、通过数量和排错入口；
6. **你需要做什么**：只有 Supabase 控制台、云服务器凭据或 Docker 环境需要用户操作；
7. **检查点**：涉及外部配置前，先让用户确认理解与验证结果。

整个后端计划期间，Nginx `/api/v1` 继续返回 404。只有后续前端与部署联调计划通过后，才开放公网代理。
