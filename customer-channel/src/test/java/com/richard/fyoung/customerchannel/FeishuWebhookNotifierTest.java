package com.richard.fyoung.customerchannel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FeishuWebhookNotifier} 未配置时的 fail-safe 行为（不发真实 HTTP）。
 */
class FeishuWebhookNotifierTest {

    @Test
    void notConfiguredSkipsPush() {
        FeishuProperties props = new FeishuProperties();
        FeishuWebhookNotifier notifier = new FeishuWebhookNotifier(props);

        assertFalse(notifier.isConfigured());
        assertEquals("skipped: webhook-url not configured", notifier.push("hello"));
    }

    @Test
    void configuredWhenWebhookUrlPresent() {
        FeishuProperties props = new FeishuProperties();
        props.setWebhookUrl("https://example.invalid/hook");
        FeishuWebhookNotifier notifier = new FeishuWebhookNotifier(props);
        assertTrue(notifier.isConfigured());
    }
}