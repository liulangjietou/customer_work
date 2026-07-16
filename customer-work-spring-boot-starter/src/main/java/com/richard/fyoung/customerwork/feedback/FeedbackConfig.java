package com.richard.fyoung.customerwork.feedback;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.feedback.mapper.FeedbackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户反馈存储配置。
 *
 * <p>按 {@code feedback.store-mode} 选择实现：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link MybatisFeedbackStore}（MyBatis-Plus，复用 {@code CustomerWorkPersistenceConfig}
 * 的独立持久化环境）。{@link FeedbackMapper} 用 {@link ObjectProvider} 惰性获取，仅 jdbc 分支取用。
 * 下游声明自己的 {@link FeedbackStore} Bean 即可整体覆盖（如 Redis 实现）。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class FeedbackConfig {

    private static final Logger log = LoggerFactory.getLogger(FeedbackConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(FeedbackStore.class)
    public FeedbackStore feedbackStore(CustomerWorkProperties properties, ObjectProvider<FeedbackMapper> mapperProvider) {
        String mode = properties.getFeedback().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("feedback store: jdbc (MyBatis-Plus 实现, table=cw_message_feedback)");
            return new MybatisFeedbackStore(mapperProvider.getObject());
        }
        log.info("feedback store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryFeedbackStore();
    }
}
