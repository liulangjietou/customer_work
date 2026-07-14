package com.richard.fyoung.customeradmin.sqlconfig.engine;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultValueResolver} 默认值表达式解析：${now} 及 ${now±N[d/h/m]}，非表达式原样返回。
 * @author owlzhangfq@gmail.com
 */
class DefaultValueResolverTest {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void shouldResolveNow() {
        String resolved = DefaultValueResolver.resolve("${now}");
        assertEquals(19, resolved.length());
        // 可被同格式解析回 LocalDateTime，说明格式正确
        LocalDateTime.parse(resolved, FORMATTER);
    }

    @Test
    void shouldResolveDaysOffset() {
        String resolved = DefaultValueResolver.resolve("${now-14d}");
        LocalDateTime value = LocalDateTime.parse(resolved, FORMATTER);
        LocalDateTime expected = LocalDateTime.now().minusDays(14);
        assertTrue(Math.abs(java.time.Duration.between(value, expected).toMinutes()) < 2);
    }

    @Test
    void shouldResolveHoursAndMinutesOffset() {
        LocalDateTime plus2h = LocalDateTime.parse(DefaultValueResolver.resolve("${now+2h}"), FORMATTER);
        assertTrue(Math.abs(java.time.Duration.between(plus2h, LocalDateTime.now().plusHours(2)).toMinutes()) < 2);

        LocalDateTime minus30m = LocalDateTime.parse(DefaultValueResolver.resolve("${now-30m}"), FORMATTER);
        assertTrue(Math.abs(java.time.Duration.between(minus30m, LocalDateTime.now().minusMinutes(30)).toMinutes()) < 2);
    }

    @Test
    void shouldReturnPlainValueAsIs() {
        assertEquals("hello", DefaultValueResolver.resolve("hello"));
        assertEquals("2026-01-01", DefaultValueResolver.resolve("2026-01-01"));
        assertNull(DefaultValueResolver.resolve(null));
    }
}
