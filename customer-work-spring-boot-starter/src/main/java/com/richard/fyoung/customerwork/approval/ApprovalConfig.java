package com.richard.fyoung.customerwork.approval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审批工单存储配置。
 *
 * <p>默认注册 {@link InMemoryApprovalStore}（进程内，离线可测）；下游声明自己的 {@link ApprovalStore}
 * Bean 即可覆盖（如 JDBC / Redis 实现），保证审批单重启不丢失——对涉及资金的退款审批至关重要。</p>
 *
 * <p>{@link PendingApprovalService} 通过构造注入获取 {@link ApprovalStore}，业务逻辑与存储解耦。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ApprovalConfig {

    @Bean
    @ConditionalOnMissingBean(ApprovalStore.class)
    public ApprovalStore approvalStore() {
        return new InMemoryApprovalStore();
    }
}
