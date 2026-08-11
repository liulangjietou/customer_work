package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import com.richard.fyoung.customeradmin.config.AdminModelFailoverProperties;
import org.springframework.stereotype.Component;

/**
 * 模型熔断状态登记表（admin 薄壳）：熔断语义已下沉到
 * {@link com.richard.fyoung.customerwork.core.model.failover.ModelCircuitBreakerRegistry}，
 * 本类只负责把 admin 的配置属性 {@code admin.model.failover.*} 绑到父类构造参数，并作为 Spring Bean 暴露。
 *
 * <p>阈值/时长在 Bean 构造时取定：{@link AdminModelFailoverProperties} 是普通
 * {@code @ConfigurationProperties}，绑定后不再变化，与"每次调用现取"等价。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ModelCircuitBreakerRegistry
        extends com.richard.fyoung.customerwork.core.model.failover.ModelCircuitBreakerRegistry {

    public ModelCircuitBreakerRegistry(AdminModelFailoverProperties properties) {
        super(properties.getFailureThreshold(), properties.getOpenDurationSeconds());
    }
}
