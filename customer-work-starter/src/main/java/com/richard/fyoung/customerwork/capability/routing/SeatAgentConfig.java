package com.richard.fyoung.customerwork.capability.routing;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.routing.mapper.SeatAgentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 坐席库存储配置。
 *
 * <p>按 {@code customer-work.routing.seat-store-mode} 选择实现：默认 {@code memory}（进程内带演示种子，离线可测）；
 * {@code jdbc} 落地为 {@link MybatisSeatAgentStore}（复用 {@code CustomerWorkPersistenceConfig} 独立持久化环境）。
 * {@link SeatAgentMapper} 用 {@link ObjectProvider} 惰性获取：memory 模式不装配 Mapper 也不报错。</p>
 *
 * <p><b>无条件装配</b>（不加 {@code @ConditionalOnProperty}）：坐席库是路由推荐的基础数据，即便
 * {@code routing.assign-enabled=false} 也保留（InMemory 近乎零开销），便于后台随时维护/预置坐席；
 * 真正是否触发分类打分由 {@code HandoffCreatedEnricher} 按开关运行时判断。下游声明自己的
 * {@link SeatAgentStore} Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SeatAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(SeatAgentConfig.class);

    @Bean
    @ConditionalOnMissingBean(SeatAgentStore.class)
    public SeatAgentStore seatAgentStore(CustomerWorkProperties properties,
                                         ObjectProvider<SeatAgentMapper> mapperProvider) {
        String mode = properties.getRouting().getSeatStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("seat-agent store: jdbc (MyBatis-Plus, table=cw_seat_agent)");
            return new MybatisSeatAgentStore(mapperProvider.getObject());
        }
        log.info("seat-agent store: memory (in-process demo seeds, use seat-store-mode=jdbc in production)");
        return new InMemorySeatAgentStore();
    }
}
