package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlFieldTransform;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FieldTransformer} 两种转换：DATE_FORMAT 时间格式化、VALUE_MAP 值映射。
 * @author owlzhangfq@gmail.com
 */
class FieldTransformerTest {

    private final FieldTransformer transformer = new FieldTransformer();

    @Test
    void dateFormat_shouldFormatTimestampAndLocalDateTime() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("created", Timestamp.valueOf(LocalDateTime.of(2026, 7, 14, 9, 30, 15)));
        rows.add(row);

        transformer.apply(List.of(transform("created", "DATE_FORMAT", "MM-dd HH:mm:ss")), rows);

        assertEquals("07-14 09:30:15", rows.get(0).get("created"));
    }

    @Test
    void dateFormat_shouldKeepOriginal_whenUnparseable() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("created", "not-a-date");
        rows.add(row);

        transformer.apply(List.of(transform("created", "DATE_FORMAT", "yyyy-MM-dd")), rows);

        assertEquals("not-a-date", rows.get(0).get("created"));
    }

    @Test
    void valueMap_shouldReplaceHit_andKeepMiss() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("status", 1);
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("status", 9);
        rows.add(hit);
        rows.add(miss);

        transformer.apply(List.of(transform("status", "VALUE_MAP", "{\"1\":\"启用\",\"0\":\"禁用\"}")), rows);

        assertEquals("启用", rows.get(0).get("status"));
        assertEquals("9", rows.get(1).get("status"));
    }

    private SqlFieldTransform transform(String field, String type, String config) {
        SqlFieldTransform t = new SqlFieldTransform();
        t.setFieldName(field);
        t.setTransformType(type);
        t.setTransformConfig(config);
        return t;
    }
}
