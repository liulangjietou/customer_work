package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 长期记忆存储装配选择单测（离线，无需 MySQL）。
 *
 * <p>覆盖三条分支：显式 {@code memory} 选进程内实现；默认（jdbc）在 Mapper 可用时选 MyBatis 实现；
 * 默认但 Mapper 缺席时<b>降级</b>回进程内而不是抛异常——降级这条是本类的重点，它保证
 * "宿主没配持久化环境" 不会演变成容器起不来。</p>
 *
 * <p>{@code jdbc} 分支的真实读写验证见 {@link MybatisLongTermMemoryStoreTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class LongTermMemoryStoreConfigTest {

    @Test
    void shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMemory().setStoreMode("memory");

        LongTermMemoryStore store = new LongTermMemoryStoreConfig()
            .longTermMemoryStore(props, provider(null));

        assertInstanceOf(InMemoryLongTermMemoryStore.class, store);
    }

    @Test
    void shouldSelectMybatis_byDefault_whenMapperAvailable() {
        CustomerWorkProperties props = new CustomerWorkProperties();

        LongTermMemoryStore store = new LongTermMemoryStoreConfig()
            .longTermMemoryStore(props, provider(mock(LongTermMemoryMapper.class)));

        assertInstanceOf(MybatisLongTermMemoryStore.class, store);
    }

    @Test
    void shouldDegradeToInMemory_whenJdbcButMapperMissing() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMemory().setStoreMode("jdbc");

        LongTermMemoryStore store = new LongTermMemoryStoreConfig()
            .longTermMemoryStore(props, provider(null));

        assertInstanceOf(InMemoryLongTermMemoryStore.class, store,
            "jdbc 但 Mapper 缺席时必须降级进程内，不能抛异常拖垮容器启动");
    }

    /** 装配分支只调 getIfAvailable，其余方法不打桩。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<LongTermMemoryMapper> provider(LongTermMemoryMapper mapper) {
        ObjectProvider<LongTermMemoryMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        return provider;
    }
}
