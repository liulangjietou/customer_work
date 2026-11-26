package com.richard.fyoung.customerchannel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FeishuNotificationChannel} 配置门禁与目标用户前缀（手写桩，不依赖 Mockito）。
 */
class FeishuNotificationChannelTest {

    static final class RecordingNotifier extends FeishuWebhookNotifier {
        private final boolean configured;
        String lastPush;

        RecordingNotifier(boolean configured) {
            super(new FeishuProperties());
            this.configured = configured;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String push(String text) {
            lastPush = text;
            return "ok";
        }
    }

    @Test
    void pushFailsFastWhenWebhookNotConfigured() {
        FeishuNotificationChannel channel = new FeishuNotificationChannel(new RecordingNotifier(false));
        assertThrows(IllegalStateException.class, () -> channel.push("u1", "msg").block());
    }

    @Test
    void pushPrefixesTargetWhenPresent() {
        RecordingNotifier notifier = new RecordingNotifier(true);
        FeishuNotificationChannel channel = new FeishuNotificationChannel(notifier);
        channel.push("u1", "hello").block();
        assertEquals("@u1 hello", notifier.lastPush);
    }

    @Test
    void pushWithoutTargetUsesMessageOnly() {
        RecordingNotifier notifier = new RecordingNotifier(true);
        FeishuNotificationChannel channel = new FeishuNotificationChannel(notifier);
        channel.push(null, "plain").block();
        assertEquals("plain", notifier.lastPush);
    }
}