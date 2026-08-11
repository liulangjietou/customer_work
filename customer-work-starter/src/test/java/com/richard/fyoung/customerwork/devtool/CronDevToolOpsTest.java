package com.richard.fyoung.customerwork.devtool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CronDevToolOps} 单测：执行时间推算、段数纠错提示、字段释义与参数边界。
 * @author owlzhangfq@gmail.com
 */
class CronDevToolOpsTest {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CronDevToolOps ops = new CronDevToolOps();

    @Test
    void explain_shouldComputeDailyNextTimes() {
        CronDevToolOps.CronExplainResult result = ops.explain("0 0 2 * * ?", 3, null);
        assertEquals("Asia/Shanghai", result.getTimezone());
        assertEquals(3, result.getNextTimes().size());
        // 每天 02:00 触发：相邻两次间隔恰好 24 小时，且时分秒固定
        List<String> times = result.getNextTimes();
        for (String time : times) {
            assertTrue(time.endsWith("02:00:00"), "每次触发都应在 02:00:00，实际 " + time);
        }
        LocalDateTime first = LocalDateTime.parse(times.get(0), FORMATTER);
        LocalDateTime second = LocalDateTime.parse(times.get(1), FORMATTER);
        assertEquals(Duration.ofDays(1), Duration.between(first, second));
    }

    @Test
    void explain_shouldComputeEveryFiveMinutes() {
        CronDevToolOps.CronExplainResult result = ops.explain("0 */5 * * * ?", 2, "UTC");
        assertEquals("UTC", result.getTimezone());
        LocalDateTime first = LocalDateTime.parse(result.getNextTimes().get(0), FORMATTER);
        LocalDateTime second = LocalDateTime.parse(result.getNextTimes().get(1), FORMATTER);
        assertEquals(Duration.ofMinutes(5), Duration.between(first, second));
    }

    @Test
    void explain_shouldDefaultToFiveTimes() {
        assertEquals(5, ops.explain("0 0 * * * ?", null, null).getNextTimes().size());
    }

    @Test
    void explain_shouldDescribeEachField() {
        List<CronDevToolOps.CronFieldDesc> fields = ops.explain("0 30 8 * * ?", 1, null).getFields();
        assertEquals(6, fields.size());
        assertEquals("秒", fields.get(0).getName());
        assertEquals("第 0 秒", fields.get(0).getDescription());
        assertEquals("第 30 分钟", fields.get(1).getDescription());
        assertEquals("每日", fields.get(3).getDescription());
        assertEquals("不指定（由另一个日期字段决定）", fields.get(5).getDescription());
    }

    @Test
    void explain_shouldDescribeStepAndRange() {
        List<CronDevToolOps.CronFieldDesc> fields = ops.explain("0 */15 9-18 * * MON-FRI", 1, null).getFields();
        assertEquals("每隔 15 分钟", fields.get(1).getDescription());
        assertEquals("第 9 到 18 小时", fields.get(2).getDescription());
    }

    /** 5 段是最高频的输入错误，提示必须给出可直接照抄的修正结果。 */
    @Test
    void explain_shouldGuideWhenUnixStyleFiveFields() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.explain("0 2 * * *", null, null));
        assertTrue(ex.getMessage().contains("5 段"));
        assertTrue(ex.getMessage().contains("\"0 0 2 * * *\""), "应给出补秒后的完整表达式，实际：" + ex.getMessage());
    }

    @Test
    void explain_shouldRejectQuartzSevenFields() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.explain("0 0 2 * * ? 2030", null, null));
        assertTrue(ex.getMessage().contains("7 段"));
    }

    @Test
    void explain_shouldRejectIllegalExpression() {
        assertThrows(IllegalArgumentException.class, () -> ops.explain("99 0 2 * * ?", null, null));
    }

    @Test
    void explain_shouldRejectBlankExpression() {
        assertThrows(IllegalArgumentException.class, () -> ops.explain("  ", null, null));
    }

    @Test
    void explain_shouldRejectOutOfRangeCount() {
        assertThrows(IllegalArgumentException.class, () -> ops.explain("0 0 2 * * ?", 0, null));
        assertThrows(IllegalArgumentException.class, () -> ops.explain("0 0 2 * * ?", 21, null));
    }

    @Test
    void explain_shouldRejectIllegalTimezone() {
        assertThrows(IllegalArgumentException.class, () -> ops.explain("0 0 2 * * ?", 1, "Mars/Base"));
    }

    /** 多余空白应被归一化，避免因复制粘贴带入的连续空格判成段数不符。 */
    @Test
    void explain_shouldNormalizeExtraWhitespace() {
        assertEquals("0 0 2 * * ?", ops.explain("  0   0  2 * *   ?  ", 1, null).getExpression());
    }
}
