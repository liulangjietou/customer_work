package com.richard.fyoung.customerwork.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FeedbackStore SPI 单测（内存实现）：save/find/findBySession，重复提交按最新覆盖。
 * @author owlzhangfq@gmail.com
 */
class FeedbackStoreTest {

    private InMemoryFeedbackStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryFeedbackStore();
    }

    @Test
    void saveAndFind_shouldStoreAndRetrieveFeedback() {
        MessageFeedback fb = new MessageFeedback("MSG-1", "s1", FeedbackType.UP, null, 1000L);
        store.save(fb);

        Optional<MessageFeedback> found = store.find("MSG-1");
        assertTrue(found.isPresent());
        assertEquals(FeedbackType.UP, found.get().type());
    }

    @Test
    void find_nonExistent_shouldReturnEmpty() {
        assertTrue(store.find("MSG-missing").isEmpty());
    }

    @Test
    void save_shouldOverwriteBySameMessageId() {
        store.save(new MessageFeedback("MSG-1", "s1", FeedbackType.UP, null, 1000L));
        store.save(new MessageFeedback("MSG-1", "s1", FeedbackType.DOWN, "改主意了", 2000L));

        MessageFeedback stored = store.find("MSG-1").orElseThrow();
        assertEquals(FeedbackType.DOWN, stored.type());
        assertEquals("改主意了", stored.comment());
    }

    @Test
    void save_null_shouldNoOp() {
        store.save(null);
        assertTrue(store.findBySession("s1").isEmpty());
    }

    @Test
    void findBySession_shouldFilterAndOrderByTime() {
        store.save(new MessageFeedback("MSG-2", "s1", FeedbackType.DOWN, null, 2000L));
        store.save(new MessageFeedback("MSG-1", "s1", FeedbackType.UP, null, 1000L));
        store.save(new MessageFeedback("MSG-3", "s2", FeedbackType.UP, null, 3000L));

        List<MessageFeedback> s1Feedback = store.findBySession("s1");
        assertEquals(2, s1Feedback.size());
        assertEquals("MSG-1", s1Feedback.get(0).messageId(), "应按时间正序");
        assertEquals("MSG-2", s1Feedback.get(1).messageId());
    }
}
