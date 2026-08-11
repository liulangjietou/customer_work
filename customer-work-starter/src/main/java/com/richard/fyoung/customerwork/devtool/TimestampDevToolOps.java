package com.richard.fyoung.customerwork.devtool;

import lombok.Builder;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 时间戳与日期时间互转纯函数集。
 *
 * <p>按输入自动判定方向：纯数字 10 位按秒、13 位按毫秒转日期时间；否则按日期时间字符串
 * （宽容解析 ISO8601 / yyyy-MM-dd HH:mm:ss / yyyy-MM-dd）转秒 + 毫秒时间戳。
 * 无 Spring 依赖、无状态，参数非法入口 fast-fail。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class TimestampDevToolOps {

    /** 默认时区。 */
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    /** 纯数字按秒解析的位数。 */
    private static final int EPOCH_SECONDS_DIGITS = 10;
    /** 纯数字按毫秒解析的位数。 */
    private static final int EPOCH_MILLIS_DIGITS = 13;

    /** 纯数字判定与统一输出格式。 */
    private static final String DIGITS_REGEX = "\\d+";
    private static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 时间戳 / 日期时间双向转换。
     *
     * @param value    纯数字时间戳（10 位秒 / 13 位毫秒）或日期时间字符串
     * @param timezone 时区，为空默认 Asia/Shanghai
     * @return 转换结果（含 datetime、秒/毫秒时间戳、时区、星期）
     */
    public TimestampResult convert(String value, String timezone) {
        DevToolArgs.requireNonBlank(value, "value");
        ZoneId zoneId = resolveZone(timezone);
        String trimmed = value.trim();

        ZonedDateTime zonedDateTime = trimmed.matches(DIGITS_REGEX)
            ? fromEpoch(trimmed, zoneId)
            : parseDateTime(trimmed, zoneId);

        Instant instant = zonedDateTime.toInstant();
        return TimestampResult.builder()
            .datetime(OUTPUT_FORMATTER.format(zonedDateTime))
            .timestampSeconds(instant.getEpochSecond())
            .timestampMillis(instant.toEpochMilli())
            .timezone(zoneId.getId())
            .dayOfWeek(zonedDateTime.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            .build();
    }

    /** 解析时区，非法直接抛出。 */
    private ZoneId resolveZone(String timezone) {
        String tz = StringUtils.hasText(timezone) ? timezone.trim() : DEFAULT_TIMEZONE;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法时区：" + tz, e);
        }
    }

    /** 纯数字时间戳（10 位秒 / 13 位毫秒）转带时区时间。 */
    private ZonedDateTime fromEpoch(String digits, ZoneId zoneId) {
        long number = Long.parseLong(digits);
        Instant instant;
        if (digits.length() == EPOCH_SECONDS_DIGITS) {
            instant = Instant.ofEpochSecond(number);
        } else if (digits.length() == EPOCH_MILLIS_DIGITS) {
            instant = Instant.ofEpochMilli(number);
        } else {
            throw new IllegalArgumentException(
                "纯数字时间戳仅支持 10 位(秒) 或 13 位(毫秒)，当前 " + digits.length() + " 位");
        }
        return instant.atZone(zoneId);
    }

    /** 宽容解析日期时间字符串：依次尝试 ISO8601(带偏移) / ISO本地 / yyyy-MM-dd HH:mm:ss / yyyy-MM-dd。 */
    private ZonedDateTime parseDateTime(String value, ZoneId zoneId) {
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(zoneId);
        } catch (Exception ignore) {
            // 尝试下一种格式
        }
        try {
            return LocalDateTime.parse(value).atZone(zoneId);
        } catch (Exception ignore) {
            // 尝试下一种格式
        }
        try {
            return LocalDateTime.parse(value, OUTPUT_FORMATTER).atZone(zoneId);
        } catch (Exception ignore) {
            // 尝试下一种格式
        }
        try {
            return LocalDate.parse(value).atStartOfDay(zoneId);
        } catch (Exception ignore) {
            // 全部失败，抛出统一说明
        }
        throw new IllegalArgumentException(
            "无法解析日期时间：" + value + "，支持 ISO8601 / yyyy-MM-dd HH:mm:ss / yyyy-MM-dd");
    }

    /**
     * 时间转换结果。
     */
    @Getter
    @Builder
    public static class TimestampResult {
        /** 格式化后的日期时间（yyyy-MM-dd HH:mm:ss）。 */
        private final String datetime;
        /** 秒级时间戳。 */
        private final long timestampSeconds;
        /** 毫秒级时间戳。 */
        private final long timestampMillis;
        /** 采用的时区 ID。 */
        private final String timezone;
        /** 星期（英文全称）。 */
        private final String dayOfWeek;
    }
}
