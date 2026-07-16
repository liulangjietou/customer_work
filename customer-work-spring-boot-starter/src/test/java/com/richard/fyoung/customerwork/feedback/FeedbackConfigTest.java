package com.richard.fyoung.customerwork.feedback;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 用户反馈存储装配选择单测（离线，无需 MySQL）：默认 memory 模式选中 {@link InMemoryFeedbackStore}。
 *
 * <p>{@code store-mode=jdbc} 分支的装配验证见 {@link MybatisFeedbackStoreTest}（需真实 MySQL，本类不覆盖）。
 * memory 分支不取用 Mapper，故 {@code mapperProvider} 传 {@code null}。</p>
 * @author owlzhangfq@gmail.com
 */
class FeedbackConfigTest {

    @Test
    void feedbackStore_shouldSelectInMemory_byDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        FeedbackStore store = new FeedbackConfig().feedbackStore(props, null);
        assertInstanceOf(InMemoryFeedbackStore.class, store);
    }

    @Test
    void feedbackStore_shouldSelectInMemory_whenStoreModeExplicitlyMemory() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getFeedback().setStoreMode("memory");
        FeedbackStore store = new FeedbackConfig().feedbackStore(props, null);
        assertInstanceOf(InMemoryFeedbackStore.class, store);
    }
}
