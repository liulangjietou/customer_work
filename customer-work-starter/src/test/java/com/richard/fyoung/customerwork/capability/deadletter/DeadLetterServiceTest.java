package com.richard.fyoung.customerwork.capability.deadletter;

import com.richard.fyoung.customerwork.infra.config.properties.DeadLetterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死信队列单测。
 *
 * <p>覆盖的是这个功能真正要防住的几件事：重投确实会发生、次数耗尽后<b>不静默丢弃</b>、
 * 退避是指数的、没有处理器时不假装在工作。</p>
 * @author owlzhangfq@gmail.com
 */
class DeadLetterServiceTest {

    private static final String TYPE = "refund-notify";

    private DeadLetterStore store;
    private DeadLetterProperties properties;

    /** 可控处理器：按需成功或失败，并记录被重投的载荷。 */
    private static final class RecordingHandler implements DeadLetterHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> payloads = new ArrayList<>();
        private volatile boolean shouldFail;

        RecordingHandler(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public void retry(DeadLetter letter) throws Exception {
            calls.incrementAndGet();
            payloads.add(letter.getPayload());
            if (shouldFail) {
                throw new IllegalStateException("downstream still down");
            }
        }
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryDeadLetterStore();
        properties = new DeadLetterProperties();
        properties.setBaseBackoffMs(0L);   // 单测里不等退避，退避逻辑另有专门用例
    }

    private DeadLetterService serviceWith(DeadLetterHandler... handlers) {
        return new DeadLetterService(store, properties, List.of(handlers));
    }

    @Test
    void record_shouldEnqueuePending() {
        DeadLetterService service = serviceWith(new RecordingHandler(false));

        DeadLetter letter = service.record(TYPE, "{\"orderNo\":\"A1\"}", "A1", "timeout").orElseThrow();

        assertEquals(DeadLetterStatus.PENDING, letter.getStatus());
        assertEquals(1, service.count(DeadLetterStatus.PENDING));
    }

    @Test
    void retryDue_shouldReplayAndMarkSucceeded() {
        RecordingHandler handler = new RecordingHandler(false);
        DeadLetterService service = serviceWith(handler);
        service.record(TYPE, "{\"orderNo\":\"A1\"}", "A1", "timeout");

        assertEquals(1, service.retryDue());

        assertEquals(1, handler.calls.get());
        assertEquals("{\"orderNo\":\"A1\"}", handler.payloads.get(0), "载荷必须自包含地传回处理器");
        assertEquals(1, service.count(DeadLetterStatus.SUCCEEDED));
        assertEquals(0, service.count(DeadLetterStatus.PENDING));
    }

    @Test
    void exhaustedRetries_shouldBeAbandonedNotDropped() {
        properties.setMaxAttempts(3);
        RecordingHandler handler = new RecordingHandler(true);
        DeadLetterService service = serviceWith(handler);
        service.record(TYPE, "{}", "A1", "timeout");

        for (int i = 0; i < 3; i++) {
            service.retryDue();
        }

        // 静默丢弃正是现在"只记 error"的问题所在，留档才能让运营捞出来手工补
        assertEquals(1, service.count(DeadLetterStatus.ABANDONED));
        List<DeadLetter> abandoned = service.list(DeadLetterStatus.ABANDONED, 10);
        assertEquals(3, abandoned.get(0).getAttempts());
        assertTrue(abandoned.get(0).getLastError().contains("downstream still down"),
            "失败原因要留着，人工补单时得知道当初为什么挂的");
    }

    @Test
    void backoffShouldGrowExponentially() {
        DeadLetter letter = new DeadLetter("id-1", TYPE, "{}", "A1", "err", 0L);

        letter.failAttempt("e1", 10, 1000L, 0L);
        long afterFirst = letter.getNextRetryAtMs();
        letter.failAttempt("e2", 10, 1000L, 0L);
        long afterSecond = letter.getNextRetryAtMs();
        letter.failAttempt("e3", 10, 1000L, 0L);
        long afterThird = letter.getNextRetryAtMs();

        // 1000*2^1、1000*2^2、1000*2^3：下游多半在重启，密集重试只会把它按在地上
        assertEquals(2000L, afterFirst);
        assertEquals(4000L, afterSecond);
        assertEquals(8000L, afterThird);
    }

    @Test
    void notDueYet_shouldNotBeRetried() {
        properties.setBaseBackoffMs(600_000L);
        RecordingHandler handler = new RecordingHandler(true);
        DeadLetterService service = serviceWith(handler);
        service.record(TYPE, "{}", "A1", "timeout");

        service.retryDue();          // 第一次重投失败，排到 10 分钟后
        int callsAfterFirst = handler.calls.get();
        service.retryDue();          // 立刻再跑一轮：还没到点，不该重投

        assertEquals(callsAfterFirst, handler.calls.get(), "退避期内不该被重复打");
    }

    @Test
    void unknownType_shouldNotConsumeAttempts() {
        DeadLetterService service = serviceWith(new RecordingHandler(false));
        service.record("no-such-type", "{}", "A1", "timeout");

        service.retryDue();
        service.retryDue();

        // 累计次数会让它悄悄耗尽变成已放弃，掩盖掉"这个类型压根没人处理"这个真正的问题
        DeadLetter letter = service.list(DeadLetterStatus.PENDING, 10).get(0);
        assertEquals(0, letter.getAttempts());
        assertEquals(DeadLetterStatus.PENDING, letter.getStatus());
    }

    @Test
    void reopen_shouldResetAttempts() {
        properties.setMaxAttempts(1);
        RecordingHandler handler = new RecordingHandler(true);
        DeadLetterService service = serviceWith(handler);
        DeadLetter letter = service.record(TYPE, "{}", "A1", "timeout").orElseThrow();
        service.retryDue();
        assertEquals(DeadLetterStatus.ABANDONED, letter.getStatus());

        DeadLetter reopened = service.reopen(letter.getId());

        assertEquals(DeadLetterStatus.PENDING, reopened.getStatus());
        assertEquals(0, reopened.getAttempts(), "不清零的话刚放回去就又立刻耗尽，等于没重置");
    }

    @Test
    void reopen_unknownId_shouldFailFast() {
        DeadLetterService service = serviceWith(new RecordingHandler(false));

        assertThrows(IllegalStateException.class, () -> service.reopen("not-exists"));
    }

    @Test
    void disabled_shouldRecordNothing() {
        properties.setEnabled(false);
        DeadLetterService service = serviceWith(new RecordingHandler(false));

        assertTrue(service.record(TYPE, "{}", "A1", "timeout").isEmpty());
        assertEquals(0, service.retryDue());
    }

    @Test
    void batchSize_shouldCapOneRound() {
        properties.setBatchSize(2);
        RecordingHandler handler = new RecordingHandler(false);
        DeadLetterService service = serviceWith(handler);
        for (int i = 0; i < 5; i++) {
            service.record(TYPE, "{\"n\":" + i + "}", "A" + i, "timeout");
        }

        assertEquals(2, service.retryDue(), "积压时一轮跑太久会拖死巡检，宁可多跑几轮");
        assertEquals(3, service.count(DeadLetterStatus.PENDING));
    }
}
