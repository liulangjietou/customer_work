package com.richard.fyoung.customerwork.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 持久化环境激活条件：任一业务域 {@code store-mode=jdbc} 或 {@code tool-backend.mode=jdbc} 时为真。
 *
 * <p>控制 {@link CustomerWorkPersistenceConfig}（独立 DataSource / SqlSessionFactory / SqlSessionTemplate +
 * 全部 Mapper）是否装配：全部为 memory / mock 时不装配任何持久化 Bean，宿主无需 MySQL 即可启动，也不会
 * 触发 Mapper 的初始化。判定在 Bean 定义注册期执行（早于 {@link CustomerWorkProperties} 绑定），
 * 故直接读 {@link Environment}，不注入已绑定的 properties 对象。</p>
 * @author owlzhangfq@gmail.com
 */
public class PersistenceJdbcCondition implements Condition {

    private static final String JDBC = "jdbc";

    /** 各域的 store-mode 配置键 + 工具后端 mode 配置键（任一为 jdbc 即激活持久化环境）。 */
    private static final String[] STORE_MODE_KEYS = {
        "customer-work.ticket.store-mode",
        "customer-work.human-approval.store-mode",
        "customer-work.slot-filling.store-mode",
        "customer-work.dialog.store-mode",
        "customer-work.human-handoff.store-mode",
        "customer-work.feedback.store-mode",
        "customer-work.user-auth.store-mode",
        "customer-work.chat-log.store-mode",
        "customer-work.attachment.store-mode",
        "customer-work.sensitive-word.store-mode",
        "customer-work.tool-backend.mode"
    };

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        for (String key : STORE_MODE_KEYS) {
            if (JDBC.equalsIgnoreCase(env.getProperty(key))) {
                return true;
            }
        }
        return false;
    }
}
