package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.HarnessMemoryMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Harness 分层记忆存储装配选择单测（离线，无需 MySQL）：显式 memory / 默认 jdbc / Mapper 缺席降级。
 *
 * <p>{@code jdbc} 分支的真实读写验证见 {@link MybatisHarnessMemoryStoreTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class HarnessMemoryStoreConfigTest {

    @Test
    void shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setMemoryStoreMode("memory");

        HarnessMemoryStore store = new HarnessMemoryStoreConfig().harnessMemoryStore(props, provider(null));

        assertInstanceOf(InMemoryHarnessMemoryStore.class, store);
    }

    @Test
    void shouldSelectMybatis_byDefault_whenMapperAvailable() {
        CustomerWorkProperties props = new CustomerWorkProperties();

        HarnessMemoryStore store = new HarnessMemoryStoreConfig()
            .harnessMemoryStore(props, provider(mock(HarnessMemoryMapper.class)));

        assertInstanceOf(MybatisHarnessMemoryStore.class, store);
    }

    @Test
    void shouldDegradeToInMemory_whenJdbcButMapperMissing() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHarness().setMemoryStoreMode("jdbc");

        HarnessMemoryStore store = new HarnessMemoryStoreConfig().harnessMemoryStore(props, provider(null));

        assertInstanceOf(InMemoryHarnessMemoryStore.class, store,
            "jdbc 但 Mapper 缺席时必须降级进程内，不能抛异常拖垮容器启动");
    }

    /** 装配分支只调 getIfAvailable，其余方法不打桩。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<HarnessMemoryMapper> provider(HarnessMemoryMapper mapper) {
        ObjectProvider<HarnessMemoryMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        return provider;
    }
}
