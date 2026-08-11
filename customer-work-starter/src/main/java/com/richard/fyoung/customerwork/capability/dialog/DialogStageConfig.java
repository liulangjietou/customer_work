package com.richard.fyoung.customerwork.capability.dialog;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.dialog.mapper.DialogStageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话阶段存储配置。
 *
 * <p>按 {@code dialog.store-mode} 选择实现：默认 {@code memory}（进程内，仅单实例适用）；
 * {@code jdbc} 落地为 {@link MybatisDialogStageStore}，复用 {@code CustomerWorkPersistenceConfig}
 * 的 MyBatis 环境（{@link DialogStageMapper} 由 {@code @MapperScan} 装配），与审批工单 / 槽位收集
 * 共享同一 MySQL 实例。下游声明自己的 {@link DialogStageStore} Bean 即可整体覆盖（如 Redis 实现）。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class DialogStageConfig {

    private static final Logger log = LoggerFactory.getLogger(DialogStageConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(DialogStageStore.class)
    public DialogStageStore dialogStageStore(CustomerWorkProperties properties,
                                             ObjectProvider<DialogStageMapper> mapperProvider) {
        String mode = properties.getDialog().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("dialog stage store: jdbc (MyBatis-Plus 实现, table=cw_dialog_stage)");
            return new MybatisDialogStageStore(mapperProvider.getObject());
        }
        log.info("dialog stage store: memory（进程内，多实例部署会导致阶段归零，生产建议 store-mode=jdbc）");
        return new InMemoryDialogStageStore();
    }
}
