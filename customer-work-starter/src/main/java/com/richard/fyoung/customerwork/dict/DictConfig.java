package com.richard.fyoung.customerwork.dict;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.dict.mapper.DictTypeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 字典存储配置。
 *
 * <p>按 {@code customer-work.dict.store-mode} 选择实现：默认 {@code memory}（进程内种子，离线可测）；
 * {@code jdbc} 落地为 {@link MybatisDictStore}（复用 {@code CustomerWorkPersistenceConfig} 的独立持久化
 * 环境）。Mapper 用 {@link ObjectProvider} 惰性获取，仅 jdbc 分支取用。
 * 下游声明自己的 {@link DictStore} Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class DictConfig {

    private static final Logger log = LoggerFactory.getLogger(DictConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(DictStore.class)
    public DictStore dictStore(CustomerWorkProperties properties,
                               ObjectProvider<DictTypeMapper> typeMapperProvider,
                               ObjectProvider<DictItemMapper> itemMapperProvider) {
        String mode = properties.getDict().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("dict store: jdbc (MyBatis-Plus 实现, tables=cw_dict_type/cw_dict_item)");
            return new MybatisDictStore(typeMapperProvider.getObject(), itemMapperProvider.getObject());
        }
        log.info("dict store: memory (进程内种子，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryDictStore();
    }
}
