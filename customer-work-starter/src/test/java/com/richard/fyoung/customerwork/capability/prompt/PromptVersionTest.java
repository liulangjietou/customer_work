package com.richard.fyoung.customerwork.capability.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词版本单测：指纹的确定性与存储的幂等性。
 *
 * <p>指纹是效果归因的支点——同内容必须同指纹（否则每次重启都像"改过提示词"），
 * 异内容必须异指纹（否则改了却看不出来）。</p>
 * @author owlzhangfq@gmail.com
 */
class PromptVersionTest {

    @Test
    void sameContent_shouldProduceSameFingerprint() {
        String prompt = "你是一个专业的客服助手。";

        assertEquals(PromptVersion.fingerprintOf(prompt), PromptVersion.fingerprintOf(prompt),
            "同内容必须同指纹，否则每次重启都会被误判成改过提示词");
    }

    @Test
    void differentContent_shouldProduceDifferentFingerprint() {
        assertNotEquals(
            PromptVersion.fingerprintOf("你是一个专业的客服助手。"),
            PromptVersion.fingerprintOf("你是一个专业的客服助手。请保持礼貌。"),
            "改了却看不出来，归因就无从谈起");
    }

    @Test
    void emptyContent_shouldProduceEmptyFingerprint() {
        assertEquals("", PromptVersion.fingerprintOf(null));
        assertEquals("", PromptVersion.fingerprintOf(""));
    }

    @Test
    void fingerprint_shouldBeShortEnoughToRead() {
        String fingerprint = PromptVersion.fingerprintOf("你是一个专业的客服助手。");

        assertEquals(16, fingerprint.length(), "64 位全量指纹在界面上没人看得下去");
        assertTrue(fingerprint.matches("[0-9a-f]+"));
    }

    @Test
    void store_shouldKeepEarliestCaptureTime() {
        PromptVersionStore store = new InMemoryPromptVersionStore();
        String prompt = "你是一个专业的客服助手。";

        store.record(PromptVersion.of(prompt, 1000L));
        store.record(PromptVersion.of(prompt, 5000L));   // 重启后再次观测到同一版

        PromptVersion stored = store.find(PromptVersion.fingerprintOf(prompt)).orElseThrow();
        assertEquals(1000L, stored.capturedAtMs(), "保留最早那次才是'这版什么时候上线的'");
    }

    @Test
    void store_shouldReturnRecentNewestFirst() {
        PromptVersionStore store = new InMemoryPromptVersionStore();
        store.record(PromptVersion.of("第一版提示词", 1000L));
        store.record(PromptVersion.of("第二版提示词", 2000L));

        List<PromptVersion> recent = store.findRecent(10);

        assertEquals(2, recent.size());
        assertEquals("第二版提示词", recent.get(0).content());
    }

    @Test
    void store_shouldIgnoreEmptyVersion() {
        PromptVersionStore store = new InMemoryPromptVersionStore();

        store.record(PromptVersion.of("", 1000L));

        assertTrue(store.findRecent(10).isEmpty(), "没有提示词就没有版本可言");
    }
}
