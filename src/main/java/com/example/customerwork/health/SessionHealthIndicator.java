package com.example.customerwork.health;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 会话持久化后端健康检查（暴露在 {@code /actuator/health}）。
 *
 * <p>对当前 {@link Session}（memory/json/redis/mysql）做一次轻量探测：能完成一次只读查询即视为 UP，
 * 否则 DOWN 并附带后端类型与错误信息，便于运维快速定位 Redis/MySQL 连接问题。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SessionHealthIndicator implements HealthIndicator {

    private static final SimpleSessionKey PROBE_KEY = SimpleSessionKey.of("__health_probe__");

    private final Session session;
    private final CustomerWorkProperties properties;

    public SessionHealthIndicator(Session session, CustomerWorkProperties properties) {
        this.session = session;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String mode = properties.getSession().getMode();
        try {
            // 只读探测：不写入数据，仅验证后端可用
            session.exists(PROBE_KEY);
            return Health.up()
                .withDetail("backend", mode)
                .withDetail("implementation", session.getClass().getSimpleName())
                .build();
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("backend", mode)
                .build();
        }
    }
}
