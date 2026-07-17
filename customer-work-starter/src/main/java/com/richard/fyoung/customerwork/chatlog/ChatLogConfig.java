package com.richard.fyoung.customerwork.chatlog;

import com.richard.fyoung.customerwork.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天日志域装配：按 {@code customer-work.chat-log.store-mode} 选择存储实现并装配服务。
 *
 * <p>默认 {@code memory}；{@code jdbc} 落地为 {@link MybatisChatMessageStore}，Mapper 由独立的
 * {@code CustomerWorkPersistenceConfig}（MyBatis-Plus 环境）统一装配，此处经 {@link ObjectProvider}
 * 惰性取用。两个 Bean 均 {@code @ConditionalOnMissingBean}，下游可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ChatLogConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatLogConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(ChatMessageStore.class)
    public ChatMessageStore chatMessageStore(CustomerWorkProperties properties,
                                             ObjectProvider<ChatMessageMapper> chatMessageMapperProvider) {
        String mode = properties.getChatLog().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("chat log store: jdbc (MyBatis-Plus 实现, table=cw_chat_message)");
            return new MybatisChatMessageStore(chatMessageMapperProvider.getObject());
        }
        log.info("chat log store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryChatMessageStore();
    }

    @Bean
    @ConditionalOnMissingBean(ChatLogService.class)
    public ChatLogService chatLogService(ChatMessageStore chatMessageStore) {
        return new ChatLogService(chatMessageStore);
    }
}
