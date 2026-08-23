package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 持久化环境激活条件：任一业务域 {@code store-mode=jdbc} 或 {@code tool-backend.mode=jdbc} 时为真。
 *
 * <p>控制 {@link CustomerWorkPersistenceConfig}（独立 DataSource / SqlSessionFactory / SqlSessionTemplate +
 * 全部 Mapper）是否装配。判定在 Bean 定义注册期执行（早于 {@link CustomerWorkProperties} 绑定），
 * 故直接读 {@link Environment}，不注入已绑定的 properties 对象——这也意味着 properties 类里的
 * Java 默认值在这里是<b>看不见</b>的，默认为 jdbc 的域必须登记进 {@link #JDBC_BY_DEFAULT_KEYS}，
 * 否则会出现"Store 想用 jdbc、但持久化环境没激活因而 Mapper 取不到"的错配。</p>
 *
 * <p><b>记忆链路默认落库（B5 起）</b>：{@code memory.store-mode}、
 * {@code memory.consent-store-mode} 与 {@code harness.memory-store-mode} 的默认值是 {@code jdbc}，
 * 故本条件默认为真、持久化环境默认装配。数据源是 HikariCP 惰性建连（构造不建连），
 * 但 Flyway 迁移启用时会主动连接并在失败时阻断启动；所以默认配置仍要求 MySQL 可用。确实需要
 * 纯内存形态的宿主可把这三个键显式配成 {@code memory} 并关闭迁移（事实日志随之降为
 * {@code NoOpFactLog}——本项目不再提供文件形态的事实日志）。</p>
 * @author owlzhangfq@gmail.com
 */
public class PersistenceJdbcCondition implements Condition {

    /** 各域的 store-mode 配置键 + 工具后端 mode 配置键（任一为 jdbc 即激活持久化环境）。 */
    private static final String[] STORE_MODE_KEYS = {
        "customer-work.ticket.store-mode",
        "customer-work.human-approval.store-mode",
        "customer-work.slot-filling.store-mode",
        "customer-work.dialog.store-mode",
        "customer-work.human-handoff.store-mode",
        "customer-work.feedback.store-mode",
        "customer-work.dict.store-mode",
        "customer-work.user-auth.store-mode",
        "customer-work.chat-log.store-mode",
        "customer-work.call-log.store-mode",
        "customer-work.outbox.store-mode",
        "customer-work.attachment.store-mode",
        "customer-work.sensitive-word.store-mode",
        // 命中日志与词表是两个独立开关：允许"词表用内存种子、只把命中记录落库"这种组合，
        // 不登记这个键的话该组合会因为持久化环境没激活、Mapper 取不到而启动失败
        "customer-work.sensitive-word.hit-log.store-mode",
        // 限流规则层同理：只开规则层、其余域全是 memory 时，也必须能激活持久化环境
        "customer-work.security.rate-limit.store-mode",
        // 主体级速率配额（等级表 + 命中记录）同理：它可能是宿主唯一想落库的东西
        "customer-work.subject-quota.store-mode",
        "customer-work.tool-backend.mode"
    };

    /**
     * 默认即 jdbc 的域：配置缺省时按 {@code jdbc} 判定（其余键缺省时按"未启用"判定）。
     *
     * <p>长期记忆与主体同意——跨会话、跨重启、跨副本才有意义，落进程内或单机磁盘等于没有。
     * 对应 {@code MemoryProperties#storeMode}、{@code MemoryProperties#consentStoreMode}
     * 的 Java 默认值；改那边的默认值必须同步改这里，否则两处判定会漂移。</p>
     */
    private static final String[] JDBC_BY_DEFAULT_KEYS = {
        "customer-work.memory.store-mode",
        // 主体授权/撤回是合规控制状态，不能因 L2 改用外部 Provider 或进程内实现而丢失
        "customer-work.memory.consent-store-mode",
        // Harness 分层记忆（MEMORY.md 的权威副本）。harness 默认关闭，本键此时无实际影响，
        // 但仍要登记：否则把上面那个键配成非 jdbc 之后，开着 harness 的宿主会静默降级进程内
        "customer-work.harness.memory-store-mode"
    };

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        for (String key : STORE_MODE_KEYS) {
            if (StoreModes.isJdbc(env.getProperty(key))) {
                return true;
            }
        }
        for (String key : JDBC_BY_DEFAULT_KEYS) {
            if (StoreModes.isJdbc(env.getProperty(key, StoreModes.JDBC))) {
                return true;
            }
        }
        return false;
    }
}
