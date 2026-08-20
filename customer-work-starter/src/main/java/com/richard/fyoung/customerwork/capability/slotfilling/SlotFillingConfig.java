package com.richard.fyoung.customerwork.capability.slotfilling;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.slotfilling.mapper.SlotFillingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 槽位收集进度存储配置。
 *
 * <p>按 {@code slot-filling.store-mode} 选择实现：默认 {@code memory}（进程内）；{@code jdbc}
 * 落地为 {@link MybatisSlotFillingStore}，复用 {@code CustomerWorkPersistenceConfig} 的 MyBatis 环境
 * （{@link SlotFillingMapper} 由 {@code @MapperScan} 装配），与审批工单共享同一 MySQL 实例。
 * 下游声明自己的 {@link SlotFillingStore} Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SlotFillingConfig {

    private static final Logger log = LoggerFactory.getLogger(SlotFillingConfig.class);

    @Bean
    @ConditionalOnMissingBean(SlotFillingStore.class)
    public SlotFillingStore slotFillingStore(CustomerWorkProperties properties,
                                             ObjectProvider<SlotFillingMapper> mapperProvider) {
        String mode = properties.getSlotFilling().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("slot-filling store: jdbc (MyBatis-Plus 实现, table=cw_slot_filling_progress)");
            return new MybatisSlotFillingStore(mapperProvider.getObject());
        }
        log.info("slot-filling store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemorySlotFillingStore();
    }
}
