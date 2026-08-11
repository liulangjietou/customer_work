package com.richard.fyoung.customerwork.capability.dialog;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.dialog.mapper.DialogStageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 对话阶段存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemoryDialogStageStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link MybatisDialogStageStoreTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class DialogStageConfigTest {

    @Test
    void dialogStageStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        DialogStageStore store = new DialogStageConfig().dialogStageStore(props, emptyProvider());
        assertInstanceOf(InMemoryDialogStageStore.class, store);
    }

    @Test
    void dialogStageStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getDialog().setStoreMode("memory");
        DialogStageStore store = new DialogStageConfig().dialogStageStore(props, emptyProvider());
        assertInstanceOf(InMemoryDialogStageStore.class, store);
    }

    /** memory 分支不解析 Mapper，返回 null 的 provider 即可（getObject 不会被调用）。 */
    private static ObjectProvider<DialogStageMapper> emptyProvider() {
        return new ObjectProvider<DialogStageMapper>() {
            @Override
            public DialogStageMapper getObject() {
                return null;
            }

            @Override
            public DialogStageMapper getObject(Object... args) {
                return null;
            }

            @Override
            public DialogStageMapper getIfAvailable() {
                return null;
            }

            @Override
            public DialogStageMapper getIfUnique() {
                return null;
            }
        };
    }
}
