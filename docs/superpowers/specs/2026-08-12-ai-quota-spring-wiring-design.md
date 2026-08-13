# AI 额度服务 Spring 装配设计

## 目标

将已经完成的 `AiQuotaService` 和 MySQL 额度仓储接入 Spring 容器，并把新用户免费次数从硬编码提升为可由环境变量覆盖的配置。默认免费次数保持为 3。

## 设计

- `AiQuotaService` 保持纯领域服务，不添加 Spring 注解，也不直接读取环境配置。
- app 模块新增 `AiQuotaProperties`，使用 `@ConfigurationProperties(prefix = "ai.quota")` 映射配置。
- `AiQuotaProperties.free` 使用整数类型，默认值为 3。
- app 模块新增 `AiQuotaConfig`，启用上述属性类，并通过 `@Bean` 将 `IAiQuotaRepository` 与配置值组装成 `IAiQuotaService`。
- 配置文件使用 `ai.quota.free: ${AI_QUOTA_FREE:3}`，允许部署环境覆盖默认值。
- 现有 `MySqlAiQuotaRepository` 继续由 Spring 的 `@Repository` 管理；MyBatis DAO 继续由 Mapper 扫描创建。

依赖方向为：

```text
Spring app 配置层 -> AiQuotaService -> IAiQuotaRepository
```

领域模块不依赖 Spring 配置层，因此未来额度来源扩展为免费赠送、拼团购买或运营赠送时，无需改变当前装配边界。

## 错误与边界

- 免费次数必须大于等于 0；负数配置在应用启动阶段拒绝，而不是等到用户首次请求时才失败。
- 未设置 `AI_QUOTA_FREE` 时使用 3。
- 本次只完成服务装配和免费次数配置，不接入控制器，也不实现拼团购买逻辑。

## 验证

- 配置属性测试验证默认值为 3，并验证负数配置不能通过校验。
- Spring 装配测试验证容器中存在唯一的 `IAiQuotaService`。
- 现有领域服务和 MySQL 仓储测试必须继续通过。
