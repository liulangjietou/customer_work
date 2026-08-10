package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 人机切换工单存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemoryHandoffStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link MybatisHandoffStoreTest}
 * （需真实 MySQL，本类不覆盖）。memory 分支不取用 Mapper，故 {@code mapperProvider} 传 {@code null}。</p>
 * @author owlzhangfq@gmail.com
 */
class HandoffConfigTest {

    @Test
    void handoffStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        HandoffStore store = new HandoffConfig().handoffStore(props, null);
        assertInstanceOf(InMemoryHandoffStore.class, store);
    }

    @Test
    void handoffStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanHandoff().setStoreMode("memory");
        HandoffStore store = new HandoffConfig().handoffStore(props, null);
        assertInstanceOf(InMemoryHandoffStore.class, store);
    }
}
