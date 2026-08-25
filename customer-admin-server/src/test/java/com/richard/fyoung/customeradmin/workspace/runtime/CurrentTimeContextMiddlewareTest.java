package com.richard.fyoung.customeradmin.workspace.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTimeContextMiddlewareTest {

    @Test
    void onSystemPrompt_shouldAppendStableZonedTime_withoutChangingUserHistory() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T13:15:30Z"), ZoneId.of("Asia/Shanghai"));
        CurrentTimeContextMiddleware middleware = new CurrentTimeContextMiddleware(clock);

        String prompt = middleware.onSystemPrompt(null, null, "你是 Java 助手。").block();

        assertTrue(prompt.startsWith("你是 Java 助手。"));
        assertTrue(prompt.contains("2026-08-24 21:15:30 星期一 +08:00 [Asia/Shanghai]"));
        assertTrue(prompt.contains("不要调用时间工具"));
    }
}
