package com.richard.fyoung.customerwork.quota;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 配额周期。
 *
 * <p>周期键（如 {@code 2026-08}）参与实时计数的 Redis key，因此必须是<b>自然日/自然月对齐</b>的，
 * 不能用"距今 N 秒"的滚动窗口——账单是按自然月出的，滚动窗口算出来的数对不上账。</p>
 * @author owlzhangfq@gmail.com
 */
public enum QuotaPeriod {

    DAILY(DateTimeFormatter.ofPattern("yyyy-MM-dd"), 2 * 24 * 3600),
    MONTHLY(DateTimeFormatter.ofPattern("yyyy-MM"), 40 * 24 * 3600);

    private final DateTimeFormatter keyFormatter;

    /** 计数键的存活时长（秒）：略大于一个周期，让跨周期的计数自然过期而不必显式清理。 */
    private final int retentionSeconds;

    QuotaPeriod(DateTimeFormatter keyFormatter, int retentionSeconds) {
        this.keyFormatter = keyFormatter;
        this.retentionSeconds = retentionSeconds;
    }

    /** 指定日期所属周期的键，如 {@code 2026-08-10}（日）或 {@code 2026-08}（月）。 */
    public String periodKey(LocalDate date) {
        return keyFormatter.format(date);
    }

    public int retentionSeconds() {
        return retentionSeconds;
    }

    /** 该周期的起始日（用于按周期聚合日用量）。 */
    public LocalDate startOf(LocalDate date) {
        return this == DAILY ? date : date.withDayOfMonth(1);
    }

    /** 宽松解析：库里存的是字符串，脏值按月周期兜底（范围更大，不会误拦）。 */
    public static QuotaPeriod parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MONTHLY;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MONTHLY;
        }
    }
}
