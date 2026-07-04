package com.richard.fyoung.customerwork.dialog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 对话阶段存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemoryDialogStageStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link JdbcDialogStageStoreTest}（需真实 MySQL）。</p>
 * @author owlzhangfq@gmail.com
 */
class DialogStageConfigTest {

    @Test
    void dialogStageStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        DialogStageStore store = new DialogStageConfig().dialogStageStore(props);
        assertInstanceOf(InMemoryDialogStageStore.class, store);
    }

    @Test
    void dialogStageStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getDialog().setStoreMode("memory");
        DialogStageStore store = new DialogStageConfig().dialogStageStore(props);
        assertInstanceOf(InMemoryDialogStageStore.class, store);
    }
}
