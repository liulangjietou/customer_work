package com.richard.fyoung.customerwork.dict;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 字典存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemoryDictStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link MybatisDictStoreTest}（需真实 MySQL，本类不覆盖）。
 * memory 分支不取用 Mapper，故两个 provider 传 {@code null}。</p>
 * @author owlzhangfq@gmail.com
 */
class DictConfigTest {

    @Test
    void dictStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        DictStore store = new DictConfig().dictStore(props, null, null);
        assertInstanceOf(InMemoryDictStore.class, store);
    }

    @Test
    void dictStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getDict().setStoreMode("memory");
        DictStore store = new DictConfig().dictStore(props, null, null);
        assertInstanceOf(InMemoryDictStore.class, store);
    }
}
