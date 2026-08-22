package com.richard.fyoung.customeradmin.configversion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** 配置版本展示脱敏器：保留结构与非敏感差异，禁止把运行时凭据和分桶盐返回浏览器。 */
final class ConfigSnapshotRedactor {

    private static final String REDACTED = "***";
    private static final String INVALID_SNAPSHOT = "{\"redacted\":true,\"reason\":\"invalid snapshot\"}";

    private final ObjectMapper objectMapper;

    ConfigSnapshotRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String redact(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            redactNode(root);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ignored) {
            // 非法历史快照也不能回退为原文，否则损坏数据可能绕过字段级脱敏。
            return INVALID_SNAPSHOT;
        }
    }

    private void redactNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    object.put(field.getKey(), REDACTED);
                } else if (isUnsafeUrl(field.getKey(), field.getValue())) {
                    // 历史 MCP URL 可能把 token 放在 userInfo/query；整段替换，避免拆分时漏掉编码变体。
                    object.put(field.getKey(), REDACTED);
                } else {
                    redactNode(field.getValue());
                }
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(this::redactNode);
        }
    }

    private boolean isSensitive(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("cipher")
            || normalized.contains("apikey")
            || normalized.contains("secret")
            || normalized.contains("password")
            || normalized.contains("credential")
            || normalized.contains("privatekey")
            || normalized.endsWith("accesstoken")
            || normalized.endsWith("refreshtoken")
            || "assignmentsalt".equals(normalized)
            || "headers".equals(normalized);
    }

    private boolean isUnsafeUrl(String fieldName, JsonNode value) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith("url") || value == null || !value.isTextual()) {
            return false;
        }
        try {
            URI uri = new URI(value.textValue());
            return uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null;
        } catch (Exception ignored) {
            // URL 字段损坏时也不能原文回显，避免畸形编码绕过凭据识别。
            return true;
        }
    }
}
