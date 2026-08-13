# AI Quota Spring Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `AiQuotaService` 注册为 Spring Bean，并以类型安全、可校验的配置提供默认 3 次免费额度。

**Architecture:** app 模块拥有 Spring 配置边界：`AiQuotaProperties` 负责绑定和校验，`AiQuotaConfig` 负责把领域服务与仓储组装起来。domain 模块保持纯 Java，不读取环境变量，也不添加 Spring 注解。

**Tech Stack:** Java 17、Spring Boot、`@ConfigurationProperties`、Jakarta Validation、JUnit 4、AssertJ、Mockito、Maven

## Global Constraints

- 默认免费次数必须为 3，并允许使用环境变量 `AI_QUOTA_FREE` 覆盖。
- 免费次数必须大于等于 0；负数配置必须使 Spring 上下文启动失败。
- `AiQuotaService` 不添加 Spring 注解，不直接读取配置。
- 本次不接入 HTTP 控制器，不实现拼团购买逻辑。
- 保留现有 MySQL Repository、MyBatis Mapper 和 UTC 时间处理行为。

---

### Task 1: 类型安全的额度配置与服务装配

**Files:**
- Create: `draw-io-front-harry-app/src/main/java/cn/bugstack/ai/config/AiQuotaProperties.java`
- Create: `draw-io-front-harry-app/src/main/java/cn/bugstack/ai/config/AiQuotaConfig.java`
- Create: `draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/config/AiQuotaConfigTest.java`

**Interfaces:**
- Consumes: `IAiQuotaRepository` 和构造器 `AiQuotaService(IAiQuotaRepository repository, int freeQuota)`。
- Produces: 配置键 `ai.quota.free`、属性方法 `int getFree()`、唯一 Spring Bean `IAiQuotaService`。

- [ ] **Step 1: 写装配行为的失败测试**

新建 `AiQuotaConfigTest.java`：

```java
package cn.bugstack.ai.test.config;

import cn.bugstack.ai.config.AiQuotaConfig;
import cn.bugstack.ai.config.AiQuotaProperties;
import cn.bugstack.ai.domain.quota.adapter.repository.IAiQuotaRepository;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AiQuotaConfigTest {

    private static final String USER_ID =
            "22222222-2222-2222-2222-222222222222";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            AiQuotaConfig.class,
                            RepositoryTestConfig.class
                    );

    @Test
    public void shouldCreateQuotaServiceWithThreeFreeUsesByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiQuotaProperties.class);
            assertThat(context).hasSingleBean(IAiQuotaService.class);
            assertThat(context.getBean(AiQuotaProperties.class).getFree())
                    .isEqualTo(3);

            IAiQuotaService service = context.getBean(IAiQuotaService.class);
            IAiQuotaRepository repository =
                    context.getBean(IAiQuotaRepository.class);

            service.getSnapshot(USER_ID);

            verify(repository).findOrCreate(USER_ID, 3);
        });
    }

    @Test
    public void shouldUseConfiguredFreeQuota() {
        contextRunner
                .withPropertyValues("ai.quota.free=5")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    IAiQuotaService service =
                            context.getBean(IAiQuotaService.class);
                    IAiQuotaRepository repository =
                            context.getBean(IAiQuotaRepository.class);

                    service.getSnapshot(USER_ID);

                    verify(repository).findOrCreate(USER_ID, 5);
                });
    }

    @Test
    public void shouldRejectNegativeFreeQuota() {
        contextRunner
                .withPropertyValues("ai.quota.free=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            );
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RepositoryTestConfig {

        @Bean
        IAiQuotaRepository aiQuotaRepository() {
            return mock(IAiQuotaRepository.class);
        }
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am `
  '-Dtest=AiQuotaConfigTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  test
```

预期：编译失败，提示找不到 `AiQuotaConfig` 和 `AiQuotaProperties`。这是正确的 RED，说明测试确实要求新增 Spring 装配。

- [ ] **Step 3: 实现配置属性类**

新建 `AiQuotaProperties.java`：

```java
package cn.bugstack.ai.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "ai.quota")
public class AiQuotaProperties {

    @Min(0)
    private int free = 3;
}
```

- [ ] **Step 4: 实现服务装配类**

新建 `AiQuotaConfig.java`：

```java
package cn.bugstack.ai.config;

import cn.bugstack.ai.domain.quota.adapter.repository.IAiQuotaRepository;
import cn.bugstack.ai.domain.quota.service.AiQuotaService;
import cn.bugstack.ai.domain.quota.service.IAiQuotaService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiQuotaProperties.class)
public class AiQuotaConfig {

    @Bean
    public IAiQuotaService aiQuotaService(
            IAiQuotaRepository repository,
            AiQuotaProperties properties
    ) {
        return new AiQuotaService(repository, properties.getFree());
    }
}
```

- [ ] **Step 5: 运行测试并确认 GREEN**

运行 Step 2 的同一条命令。

预期：`Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 6: 提交本任务**

```powershell
git add -- `
  draw-io-front-harry-app/src/main/java/cn/bugstack/ai/config/AiQuotaProperties.java `
  draw-io-front-harry-app/src/main/java/cn/bugstack/ai/config/AiQuotaConfig.java `
  draw-io-front-harry-app/src/test/java/cn/bugstack/ai/test/config/AiQuotaConfigTest.java
git commit -m "feat: wire AI quota service configuration"
```

---

### Task 2: 部署配置与完整回归

**Files:**
- Modify: `draw-io-front-harry-app/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 1 定义的 `ai.quota.free`。
- Produces: 环境变量入口 `AI_QUOTA_FREE`，未配置时值为 3。

- [ ] **Step 1: 在全局配置中增加环境变量映射**

在 `application.yml` 的现有 `spring` 配置之后加入：

```yaml
ai:
  quota:
    free: ${AI_QUOTA_FREE:3}
```

该配置放在全局文件而不是 dev/prod 文件中，保证所有 profile 的配置键和默认值一致。

- [ ] **Step 2: 运行配置、领域和数据库回归测试**

确保 Docker Desktop 正常运行，然后执行：

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'

mvn.cmd -q -pl draw-io-front-harry-app -am `
  '-Dapi.version=1.44' `
  '-Dtest=AiQuotaConfigTest,AiQuotaServiceTest,MySqlAiQuotaRepositoryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  test
```

预期：三个测试类全部通过；其中配置测试 3 个，MySQL 仓储测试 14 个。

- [ ] **Step 3: 验证构建和变更格式**

```powershell
mvn.cmd -q -pl draw-io-front-harry-app -am -DskipTests package
git diff --check
git status --short
```

预期：Maven 返回 0，`git diff --check` 无输出；`git status --short` 只包含当前额度任务的预期文件。

- [ ] **Step 4: 提交配置**

```powershell
git add -- draw-io-front-harry-app/src/main/resources/application.yml
git commit -m "config: set default AI quota"
```
