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
        return new AiQuotaService(
                repository,
                properties.getFree()
        );
    }
}