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
            assertThat(
                    context.getBean(AiQuotaProperties.class).getFree()
            ).isEqualTo(3);

            IAiQuotaService service =
                    context.getBean(IAiQuotaService.class);
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
