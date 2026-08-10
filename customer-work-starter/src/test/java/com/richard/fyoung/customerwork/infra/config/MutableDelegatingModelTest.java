package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MutableDelegatingModel} 单测：委托正确性 + swap 原子替换 + 并发 swap/read 安全。
 * @author owlzhangfq@gmail.com
 */
class MutableDelegatingModelTest {

    @Test
    void delegatesToCurrent() {
        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("m1");
        when(initial.getContextWindowSize()).thenReturn(4096);
        MutableDelegatingModel model = new MutableDelegatingModel(initial);
        assertEquals("m1", model.getModelName());
        assertEquals(4096, model.getContextWindowSize());
        assertSame(initial, model.current());
    }

    @Test
    void swapReplacesDelegateAtomically() {
        Model a = mock(Model.class);
        Model b = mock(Model.class);
        when(a.getModelName()).thenReturn("a");
        when(b.getModelName()).thenReturn("b");
        MutableDelegatingModel model = new MutableDelegatingModel(a);
        model.swap(b);
        assertSame(b, model.current());
        assertEquals("b", model.getModelName());
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new MutableDelegatingModel(null));
        MutableDelegatingModel model = new MutableDelegatingModel(mock(Model.class));
        assertThrows(IllegalArgumentException.class, () -> model.swap(null));
    }

    @Test
    void concurrentSwapAndReadIsSafe() throws Exception {
        Model initial = mock(Model.class);
        when(initial.getModelName()).thenReturn("init");
        MutableDelegatingModel model = new MutableDelegatingModel(initial);

        int threads = 16;
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < threads; i++) {
            final boolean writer = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < iterations; n++) {
                        if (writer) {
                            Model next = mock(Model.class);
                            when(next.getModelName()).thenReturn("m" + n);
                            model.swap(next);
                        } else {
                            // 读永不为空、永不抛（原子引用保证）
                            assertTrue(model.current() != null);
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");
        assertTrue(failure.get() == null, "并发 swap/read 不应抛异常: " + failure.get());
    }
}
