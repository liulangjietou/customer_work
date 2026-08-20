package com.richard.fyoung.customerwork.capability.badcase;

import com.richard.fyoung.customerwork.capability.badcase.mapper.BadcaseMapper;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;
import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessageStore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * badcase 存储装配。
 *
 * <p>按 {@code badcase.store-mode} 选择实现：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link MybatisBadcaseStore}。下游声明自己的 {@link BadcaseStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class BadcaseConfig {

    private static final Logger log = LoggerFactory.getLogger(BadcaseConfig.class);

    @Bean
    @ConditionalOnMissingBean(BadcaseStore.class)
    public BadcaseStore badcaseStore(CustomerWorkProperties properties,
                                     ObjectProvider<BadcaseMapper> mapperProvider) {
        String mode = properties.getBadcase().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("badcase store: jdbc (MyBatis-Plus 实现, table=cw_badcase)");
            return new MybatisBadcaseStore(mapperProvider.getObject());
        }
        log.info("badcase store: memory (进程内，重启清空待筛队列，生产建议 store-mode=jdbc)");
        return new InMemoryBadcaseStore();
    }

    /**
     * badcase 回流服务。
     *
     * <p>两个可选协作者在 {@code @Bean} 方法体内用 {@link ObjectProvider} 解析，而不是靠
     * {@code @ConditionalOnBean}：后者在 Bean <b>定义注册阶段</b>判定，与本配置类的装配先后有关、
     * 会随机失效；而方法体执行时全部 Bean 定义都已注册完毕，查找结果是确定的。</p>
     */
    @Bean
    @ConditionalOnMissingBean(BadcaseService.class)
    public BadcaseService badcaseService(BadcaseStore badcaseStore,
                                         EvalCaseStore evalCaseStore,
                                         ObjectProvider<ChatMessageStore> chatStoreProvider,
                                         ObjectProvider<KnowledgeMapper> knowledgeMapperProvider) {
        return new BadcaseService(badcaseStore, evalCaseStore,
            chatStoreProvider.getIfAvailable(), knowledgeMapperProvider.getIfAvailable());
    }
}
