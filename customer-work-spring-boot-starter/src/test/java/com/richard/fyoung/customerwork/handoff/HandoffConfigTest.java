package com.richard.fyoung.customerwork.handoff;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 人机切换工单存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemoryHandoffStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link JdbcHandoffStoreTest}
 * （需真实 MySQL，本类不覆盖——JDBC 连接池首次取连接失败会抛出非受检异常，不适合无 DB 环境构造）。</p>
 * @author owlzhangfq@gmail.com
 */
class HandoffConfigTest {

    @Test
    void handoffStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        HandoffStore store = new HandoffConfig().handoffStore(props);
        assertInstanceOf(InMemoryHandoffStore.class, store);
    }

    @Test
    void handoffStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanHandoff().setStoreMode("memory");
        HandoffStore store = new HandoffConfig().handoffStore(props);
        assertInstanceOf(InMemoryHandoffStore.class, store);
    }
}
