package com.richard.fyoung.customerwork.infra.sqlkit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.CollectionUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结果列转换器：对结果集按配置做展示层转换。
 * <ul>
 *   <li>DATE_FORMAT：config 为目标格式串，尽力把 Date/LocalDateTime/Timestamp/字符串解析后格式化，失败原样保留；</li>
 *   <li>VALUE_MAP：config 为 JSON {"原值":"显示值"}，命中替换、未命中原样保留。</li>
 * </ul>
 *
 * <p>入参用 {@link FieldTransform} 记录而非某个持久化实体，让本类与调用方的表结构解耦；
 * 调用方把自己的实体转成记录再传入即可。本类无状态、可安全复用，starter 侧不注册为 Bean。</p>
 * @author owlzhangfq@gmail.com
 */
public class FieldTransformer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter[] FALLBACK_PARSERS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    /**
     * 一条列转换配置。
     *
     * @param fieldName       结果集列名
     * @param transformType   {@link TransformType} 名称（大小写不敏感，非法值整条跳过）
     * @param transformConfig DATE_FORMAT 为格式串；VALUE_MAP 为 JSON 映射 {"原值":"显示值"}
     */
    public record FieldTransform(String fieldName, String transformType, String transformConfig) {
    }

    /** 对结果行集应用全部转换器（原地修改行 Map）。 */
    public void apply(List<FieldTransform> transforms, List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(transforms) || CollectionUtils.isEmpty(rows)) {
            return;
        }
        for (FieldTransform transform : transforms) {
            String field = transform.fieldName();
            if (!TransformType.isValid(transform.transformType())) {
                continue;
            }
            TransformType type = TransformType.valueOf(transform.transformType().toUpperCase());
            Map<String, String> valueMap = type == TransformType.VALUE_MAP ? parseValueMap(transform.transformConfig()) : null;
            for (Map<String, Object> row : rows) {
                if (!row.containsKey(field)) {
                    continue;
                }
                Object original = row.get(field);
                Object converted = type == TransformType.DATE_FORMAT
                    ? applyDateFormat(original, transform.transformConfig())
                    : applyValueMap(original, valueMap);
                row.put(field, converted);
            }
        }
    }

    private Object applyDateFormat(Object value, String pattern) {
        if (value == null) {
            return null;
        }
        LocalDateTime dateTime = toLocalDateTime(value);
        if (dateTime == null) {
            return value;
        }
        try {
            return DateTimeFormatter.ofPattern(pattern).format(dateTime);
        } catch (Exception e) {
            return value;
        }
    }

    private Object applyValueMap(Object value, Map<String, String> valueMap) {
        if (value == null || CollectionUtils.isEmpty(valueMap)) {
            return value;
        }
        String key = String.valueOf(value);
        return valueMap.getOrDefault(key, key);
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().atStartOfDay();
        }
        if (value instanceof LocalDate ld) {
            return ld.atStartOfDay();
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
        if (value instanceof String str) {
            return parseString(str.trim());
        }
        return null;
    }

    private LocalDateTime parseString(String text) {
        for (DateTimeFormatter parser : FALLBACK_PARSERS) {
            try {
                if (parser == FALLBACK_PARSERS[2]) {
                    return LocalDate.parse(text, parser).atStartOfDay();
                }
                return LocalDateTime.parse(text, parser);
            } catch (Exception ignored) {
                // 尝试下一个解析器
            }
        }
        return null;
    }

    private Map<String, String> parseValueMap(String config) {
        try {
            Map<String, String> parsed = MAPPER.readValue(config, new TypeReference<HashMap<String, String>>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
