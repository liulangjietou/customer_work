package com.richard.fyoung.customerwork.health;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.state.AgentStateStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 会话状态后端健康检查（暴露在 {@code /actuator/health}）。
 *
 * <p>对当前 {@link AgentStateStore}（memory/json/redis/mysql）做一次轻量只读探测：能完成一次
 * {@code exists} 查询即视为 UP，否则 DOWN 并附带后端类型与错误信息，便于运维快速定位
 * Redis/MySQL 连接问题。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SessionHealthIndicator implements HealthIndicator {

    private static final String PROBE_USER = "__health_probe__";
    private static final String PROBE_SESSION = "__health_probe__";

    private final AgentStateStore stateStore;
    private final CustomerWorkProperties properties;

    public SessionHealthIndicator(AgentStateStore stateStore, CustomerWorkProperties properties) {
        this.stateStore = stateStore;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String mode = properties.getSession().getMode();
        try {
            // 只读探测：不写入数据，仅验证后端可用
            stateStore.exists(PROBE_USER, PROBE_SESSION);
            return Health.up()
                .withDetail("backend", mode)
                .withDetail("implementation", stateStore.getClass().getSimpleName())
                .build();
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("backend", mode)
                .build();
        }
    }
}
