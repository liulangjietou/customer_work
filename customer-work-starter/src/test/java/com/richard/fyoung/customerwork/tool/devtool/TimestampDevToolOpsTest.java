package com.richard.fyoung.customerwork.tool.devtool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimestampDevToolOps} 单测：10/13 位自动识别、日期时间宽容解析、时区与非法输入。
 * @author owlzhangfq@gmail.com
 */
class TimestampDevToolOpsTest {

    private final TimestampDevToolOps ops = new TimestampDevToolOps();

    @Test
    void convert_shouldParse10DigitsAsSeconds() {
        TimestampDevToolOps.TimestampResult r = ops.convert("1721520000", null);
        assertEquals(1721520000L, r.getTimestampSeconds());
        assertEquals(1721520000000L, r.getTimestampMillis());
        assertEquals("Asia/Shanghai", r.getTimezone());
        assertNotNull(r.getDatetime());
        assertTrue(r.getDayOfWeek() != null && !r.getDayOfWeek().isEmpty());
    }

    @Test
    void convert_shouldParse13DigitsAsMillis() {
        TimestampDevToolOps.TimestampResult r = ops.convert("1721520000000", null);
        assertEquals(1721520000L, r.getTimestampSeconds());
        assertEquals(1721520000000L, r.getTimestampMillis());
    }

    @Test
    void convert_shouldParseDateTimeString_andRoundTrip() {
        TimestampDevToolOps.TimestampResult r = ops.convert("2026-07-21 10:00:00", "Asia/Shanghai");
        assertEquals("2026-07-21 10:00:00", r.getDatetime());
        // 用返回的秒时间戳（10 位）反向再转，datetime 应一致
        TimestampDevToolOps.TimestampResult back = ops.convert(String.valueOf(r.getTimestampSeconds()), "Asia/Shanghai");
        assertEquals("2026-07-21 10:00:00", back.getDatetime());
    }

    @Test
    void convert_shouldParseDateOnly() {
        TimestampDevToolOps.TimestampResult r = ops.convert("2026-07-21", "Asia/Shanghai");
        assertEquals("2026-07-21 00:00:00", r.getDatetime());
    }

    @Test
    void convert_shouldParseIso8601WithOffset() {
        TimestampDevToolOps.TimestampResult r = ops.convert("2026-07-21T10:00:00+08:00", "Asia/Shanghai");
        assertEquals("2026-07-21 10:00:00", r.getDatetime());
    }

    @Test
    void convert_shouldRejectIllegalDigitLength() {
        assertThrows(IllegalArgumentException.class, () -> ops.convert("123", null));
    }

    @Test
    void convert_shouldRejectUnparseableDateTime() {
        assertThrows(IllegalArgumentException.class, () -> ops.convert("not-a-date", null));
    }

    @Test
    void convert_shouldRejectIllegalTimezone() {
        assertThrows(IllegalArgumentException.class, () -> ops.convert("1721520000", "Not/AZone"));
    }

    @Test
    void convert_shouldRejectBlank() {
        assertThrows(IllegalArgumentException.class, () -> ops.convert("  ", null));
    }
}
