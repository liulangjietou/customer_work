package com.richard.fyoung.customeradmin.common.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 操作日志参数脱敏工具：按字段名关键字掩码，供 {@link OperationLogAspect} 记录请求参数前调用。
 *
 * <p>与 {@code customer-work-spring-boot-starter} 里同名的 {@code SensitiveDataMasker}
 * （面向客服对话文本内容做手机号/身份证等正则脱敏）语义不同——本类面向"请求参数对象"按
 * <b>字段名</b>掩码，不做文本内容正则扫描，两者不复用。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SensitiveDataMasker {

    private static final List<String> SENSITIVE_KEYWORDS =
        List.of("apikey", "password", "secret", "token");

    private static final String MASK = "******";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 把参数对象序列化为 JSON 后按字段名关键字掩码；序列化失败时返回 toString（不阻断主流程）。 */
    public String maskToJson(Object params) {
        if (params == null) {
            return null;
        }
        try {
            JsonNode node = mapper.valueToTree(params);
            maskNode(node);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(params);
        }
    }

    private void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (isSensitiveField(entry.getKey())) {
                    obj.put(entry.getKey(), MASK);
                } else {
                    maskNode(entry.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                maskNode(child);
            }
        }
    }

    private boolean isSensitiveField(String fieldName) {
        String lower = fieldName.toLowerCase();
        return SENSITIVE_KEYWORDS.stream().anyMatch(lower::contains);
    }
}
