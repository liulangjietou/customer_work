package com.richard.fyoung.customerchannel.access.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyedSerialExecutor} 按 key 串行、跨 key 并行与异常隔离。
 */
class KeyedSerialExecutorTest {

    @Test
    void sameKeyWaitsForPriorTask() throws Exception {
        try (KeyedSerialExecutor exec = new KeyedSerialExecutor("test-serial")) {
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicReference<Integer> secondSaw = new AtomicReference<>();

            exec.submit("session-a", () -> {
                firstEntered.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "first task should start");

            CountDownLatch secondDone = new CountDownLatch(1);
            exec.submit("session-a", () -> {
                secondSaw.set(1);
                secondDone.countDown();
            });

            Thread.sleep(100);
            assertEquals(null, secondSaw.get(), "second task on same key must wait");

            releaseFirst.countDown();
            assertTrue(secondDone.await(5, TimeUnit.SECONDS), "second task should run after first releases");
            assertEquals(1, secondSaw.get());
        }
    }

    @Test
    void differentKeysRunInParallel() throws Exception {
        try (KeyedSerialExecutor exec = new KeyedSerialExecutor("test-parallel")) {
            CountDownLatch aEntered = new CountDownLatch(1);
            CountDownLatch bEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            exec.submit("key-a", () -> {
                aEntered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            exec.submit("key-b", () -> {
                bEntered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(aEntered.await(5, TimeUnit.SECONDS));
            assertTrue(bEntered.await(5, TimeUnit.SECONDS), "other key should not wait on first key");
            release.countDown();
        }
    }

    @Test
    void failedTaskDoesNotBlockNextOnSameKey() throws Exception {
        try (KeyedSerialExecutor exec = new KeyedSerialExecutor("test-fail")) {
            AtomicBoolean secondRan = new AtomicBoolean();
            CountDownLatch secondDone = new CountDownLatch(1);

            exec.submit("k", () -> {
                throw new RuntimeException("boom");
            });
            exec.submit("k", () -> {
                secondRan.set(true);
                secondDone.countDown();
            });

            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertTrue(secondRan.get());
        }
    }

    @Test
    void closeAfterSubmittedTasks() throws Exception {
        KeyedSerialExecutor exec = new KeyedSerialExecutor("test-close");
        CountDownLatch done = new CountDownLatch(1);
        exec.submit("k", done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        exec.close();
    }
}