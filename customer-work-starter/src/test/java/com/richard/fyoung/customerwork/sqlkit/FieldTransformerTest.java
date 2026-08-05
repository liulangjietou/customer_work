package com.richard.fyoung.customerwork.sqlkit;

import com.richard.fyoung.customerwork.sqlkit.FieldTransformer.FieldTransform;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FieldTransformer} 两种转换：DATE_FORMAT 时间格式化、VALUE_MAP 值映射，
 * 以及非法转换类型/未命中列的跳过行为。
 * @author owlzhangfq@gmail.com
 */
class FieldTransformerTest {

    private final FieldTransformer transformer = new FieldTransformer();

    @Test
    void dateFormat_shouldFormatTimestampAndLocalDateTime() {
        List<Map<String, Object>> rows = rows(row("created",
            Timestamp.valueOf(LocalDateTime.of(2026, 7, 14, 9, 30, 15))));

        transformer.apply(List.of(transform("created", "DATE_FORMAT", "MM-dd HH:mm:ss")), rows);

        assertEquals("07-14 09:30:15", rows.get(0).get("created"));
    }

    @Test
    void dateFormat_shouldParseStringValue() {
        List<Map<String, Object>> rows = rows(row("created", "2026-07-14 09:30:15"));

        transformer.apply(List.of(transform("created", "date_format", "yyyy/MM/dd")), rows);

        assertEquals("2026/07/14", rows.get(0).get("created"));
    }

    @Test
    void dateFormat_shouldKeepOriginal_whenUnparseable() {
        List<Map<String, Object>> rows = rows(row("created", "not-a-date"));

        transformer.apply(List.of(transform("created", "DATE_FORMAT", "yyyy-MM-dd")), rows);

        assertEquals("not-a-date", rows.get(0).get("created"));
    }

    @Test
    void valueMap_shouldReplaceHit_andKeepMiss() {
        List<Map<String, Object>> rows = rows(row("status", 1), row("status", 9));

        transformer.apply(List.of(transform("status", "VALUE_MAP", "{\"1\":\"启用\",\"0\":\"禁用\"}")), rows);

        assertEquals("启用", rows.get(0).get("status"));
        assertEquals("9", rows.get(1).get("status"));
    }

    @Test
    void valueMap_shouldKeepOriginal_whenConfigIsNotJson() {
        List<Map<String, Object>> rows = rows(row("status", 1));

        transformer.apply(List.of(transform("status", "VALUE_MAP", "not-json")), rows);

        assertEquals(1, rows.get(0).get("status"));
    }

    @Test
    void shouldSkip_whenTransformTypeInvalidOrColumnAbsent() {
        List<Map<String, Object>> rows = rows(row("status", 1));

        transformer.apply(List.of(transform("status", "UNKNOWN", "{}")), rows);
        transformer.apply(List.of(transform("not-exist", "VALUE_MAP", "{\"1\":\"启用\"}")), rows);

        assertEquals(1, rows.get(0).get("status"));
    }

    @Test
    void shouldDoNothing_whenTransformsOrRowsEmpty() {
        List<Map<String, Object>> rows = rows(row("status", 1));

        transformer.apply(List.of(), rows);
        transformer.apply(List.of(transform("status", "VALUE_MAP", "{\"1\":\"启用\"}")), List.of());

        assertEquals(1, rows.get(0).get("status"));
    }

    private FieldTransform transform(String field, String type, String config) {
        return new FieldTransform(field, type, config);
    }

    private Map<String, Object> row(String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        return row;
    }

    @SafeVarargs
    private List<Map<String, Object>> rows(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }
}
