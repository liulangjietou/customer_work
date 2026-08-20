package com.richard.fyoung.customerwork.capability.prompt;

import com.richard.fyoung.customerwork.capability.prompt.mapper.PromptVersionMapper;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词版本追踪装配。
 *
 * <p>按 {@code prompt-version.store-mode} 选择存储；默认 memory。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class PromptVersionConfig {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionConfig.class);

    @Bean
    @ConditionalOnMissingBean(PromptVersionStore.class)
    public PromptVersionStore promptVersionStore(CustomerWorkProperties properties,
                                                 ObjectProvider<PromptVersionMapper> mapperProvider) {
        String mode = properties.getPromptVersion().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("prompt version store: jdbc (MyBatis-Plus 实现, table=cw_prompt_version)");
            return new MybatisPromptVersionStore(mapperProvider.getObject());
        }
        log.info("prompt version store: memory (进程内，重启丢历史版本，生产建议 store-mode=jdbc)");
        return new InMemoryPromptVersionStore();
    }

    @Bean
    @ConditionalOnMissingBean(PromptVersionTracker.class)
    public PromptVersionTracker promptVersionTracker(CustomerServiceAgentFactory agentFactory,
                                                     PromptVersionStore store) {
        return new PromptVersionTracker(agentFactory, store);
    }
}
