package com.richard.fyoung.customerwork.slotfilling;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 槽位收集存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemorySlotFillingStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link JdbcSlotFillingStoreTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class SlotFillingConfigTest {

    @Test
    void slotFillingStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        SlotFillingStore store = new SlotFillingConfig().slotFillingStore(props);
        assertInstanceOf(InMemorySlotFillingStore.class, store);
    }

    @Test
    void slotFillingStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSlotFilling().setStoreMode("memory");
        SlotFillingStore store = new SlotFillingConfig().slotFillingStore(props);
        assertInstanceOf(InMemorySlotFillingStore.class, store);
    }
}
