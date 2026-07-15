package com.richard.fyoung.customerwork.chatlog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 聊天日志域装配：按 {@code customer-work.chat-log.store-mode} 选择存储实现并装配服务。
 *
 * <p>结构对齐 {@code HandoffConfig}：默认 {@code memory}；{@code jdbc} 落地为 {@link JdbcChatMessageStore}，
 * 复用 {@code session.mysql.*} 连接配置。两个 Bean 均 {@code @ConditionalOnMissingBean}，下游可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ChatLogConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatLogConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(ChatMessageStore.class)
    public ChatMessageStore chatMessageStore(CustomerWorkProperties properties) {
        String mode = properties.getChatLog().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("chat log store: jdbc (mysql, table=cw_chat_message)");
            return new JdbcChatMessageStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("chat log store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryChatMessageStore();
    }

    @Bean
    @ConditionalOnMissingBean(ChatLogService.class)
    public ChatLogService chatLogService(ChatMessageStore chatMessageStore) {
        return new ChatLogService(chatMessageStore);
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-chatlog-pool");
        return ds;
    }
}
