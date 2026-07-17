package com.richard.fyoung.customerwork.slotfilling;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.slotfilling.mapper.SlotFillingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 槽位收集存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemorySlotFillingStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link MybatisSlotFillingStoreTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class SlotFillingConfigTest {

    @Test
    void slotFillingStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        SlotFillingStore store = new SlotFillingConfig().slotFillingStore(props, emptyProvider());
        assertInstanceOf(InMemorySlotFillingStore.class, store);
    }

    @Test
    void slotFillingStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSlotFilling().setStoreMode("memory");
        SlotFillingStore store = new SlotFillingConfig().slotFillingStore(props, emptyProvider());
        assertInstanceOf(InMemorySlotFillingStore.class, store);
    }

    /** memory 分支不解析 Mapper，返回 null 的 provider 即可（getObject 不会被调用）。 */
    private static ObjectProvider<SlotFillingMapper> emptyProvider() {
        return new ObjectProvider<SlotFillingMapper>() {
            @Override
            public SlotFillingMapper getObject() {
                return null;
            }

            @Override
            public SlotFillingMapper getObject(Object... args) {
                return null;
            }

            @Override
            public SlotFillingMapper getIfAvailable() {
                return null;
            }

            @Override
            public SlotFillingMapper getIfUnique() {
                return null;
            }
        };
    }
}
