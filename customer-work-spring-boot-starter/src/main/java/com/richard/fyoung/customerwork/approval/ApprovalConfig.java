package com.richard.fyoung.customerwork.approval;

import com.richard.fyoung.customerwork.approval.mapper.ApprovalMapper;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审批工单存储配置。
 *
 * <p>按 {@code human-approval.store-mode} 选择实现：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link MybatisApprovalStore}，保证审批单重启 / 多实例部署不丢失——对涉及资金的
 * 退款审批至关重要。jdbc 模式复用 {@code CustomerWorkPersistenceConfig} 的 MyBatis 环境（{@link ApprovalMapper}
 * 由 {@code @MapperScan} 装配，惰性获取），与会话持久化共享同一 MySQL 实例。</p>
 *
 * <p>下游声明自己的 {@link ApprovalStore} Bean 即可整体覆盖（如 Redis 实现）。
 * {@link PendingApprovalService} 通过构造注入获取 {@link ApprovalStore}，业务逻辑与存储解耦。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ApprovalConfig {

    private static final Logger log = LoggerFactory.getLogger(ApprovalConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(ApprovalStore.class)
    public ApprovalStore approvalStore(CustomerWorkProperties properties,
                                       ObjectProvider<ApprovalMapper> mapperProvider) {
        String mode = properties.getHumanApproval().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("approval store: jdbc (MyBatis-Plus 实现, table=cw_approval)");
            return new MybatisApprovalStore(mapperProvider.getObject());
        }
        log.info("approval store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryApprovalStore();
    }
}
