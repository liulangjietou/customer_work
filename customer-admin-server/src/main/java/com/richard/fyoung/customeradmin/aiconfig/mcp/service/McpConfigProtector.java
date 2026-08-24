package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.tool.mcp.McpServerSpec;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MCP 配置的展示脱敏与更新合并边界。
 *
 * <p>浏览器只拿到显式占位符；更新时只有连接目标未变化，才允许占位符按原 JSON 路径复用旧值。
 * 远程地址或 stdio 执行目标变化时必须重新提交凭据，避免把旧凭据发送给新目标。</p>
 */
final class McpConfigProtector {

    static final String SECRET_PLACEHOLDER = "__MCP_SECRET_REDACTED__";
    static final String SECRET_REF_MARKER = "__MCP_SECRET_REF__";

    private static final String INVALID_CONFIG = "{\"redacted\":true,\"reason\":\"invalid config\"}";
    private static final String HTTP_SCHEME = "http";
    private static final List<String> WORKING_DIRECTORY_KEYS = List.of("workingDirectory", "workingDir", "cwd");

    private final ObjectMapper objectMapper;

    McpConfigProtector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 新建必须提交完整配置，占位符没有可复用的服务端事实。 */
    String prepareForCreate(String mcpType, String config) {
        JsonNode root = parse(config);
        if (containsPlaceholder(root) || containsSecretRefMarker(root)) {
            throw invalid("新建 MCP 不能使用脱敏占位符，请重新提供 secret");
        }
        validateTarget(mcpType, root);
        return write(root);
    }

    /**
     * 更新允许在敏感字段或 headers 的值位置提交占位符；连接目标一致时才从持久化配置按相同路径恢复旧值。
     */
    String prepareForUpdate(String currentType, String currentConfig, String submittedType, String submittedConfig) {
        JsonNode submitted = parse(submittedConfig);
        if (containsSecretRefMarker(submitted)) {
            throw invalid("MCP SecretRef 内部占位符不能由客户端提交");
        }
        boolean hasPlaceholder = containsPlaceholder(submitted);
        if (hasPlaceholder) {
            validatePlaceholderPositions(submitted, false);
        }
        TargetIdentity submittedTarget = validateTarget(submittedType, submitted);
        if (!hasPlaceholder) {
            return write(submitted);
        }
        JsonNode current = parse(currentConfig);
        TargetIdentity currentTarget = validateTarget(currentType, current);
        if (!Objects.equals(currentTarget, submittedTarget)) {
            throw invalid("MCP 连接目标已变化，不能复用旧 secret，请重新提供全部凭据");
        }
        return write(mergePlaceholders(submitted, current, false));
    }

    private void validatePlaceholderPositions(JsonNode node, boolean protectedPosition) {
        if (isPlaceholder(node)) {
            if (!protectedPosition) {
                throw invalid("脱敏占位符只能用于敏感字段更新");
            }
            return;
        }
        if (node == null || node.isValueNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                boolean childProtected = protectedPosition
                    || isHeaders(field.getKey()) || isSensitiveKey(field.getKey());
                validatePlaceholderPositions(field.getValue(), childProtected);
            }
            return;
        }
        node.forEach(child -> validatePlaceholderPositions(child, protectedPosition));
    }

    /** 展示时保留非敏感结构，敏感键与 headers 下的所有叶子值统一替换为显式占位符。 */
    String redactForDetail(String mcpType, String config) {
        try {
            JsonNode root = objectMapper.readTree(config);
            if (root == null || !root.isObject()) {
                return INVALID_CONFIG;
            }
            // 存量脏数据也不能让 URL 中的 userInfo/query/fragment 通过详情接口泄露。
            validateTarget(mcpType, root);
            redactNode(root);
            return write(root);
        } catch (Exception ignored) {
            // 损坏的存量配置也不能回退原文，否则异常数据会绕过字段级脱敏。
            return INVALID_CONFIG;
        }
    }

    /** 把 headers、token、password 等敏感叶子从可执行配置剥离为独立 JSON 材料。 */
    SecretExtraction extractSecrets(String normalizedConfig) {
        JsonNode root = parse(normalizedConfig).deepCopy();
        ObjectNode secretBundle = objectMapper.createObjectNode();
        JsonNode protectedRoot = extractNode(root, false, "", secretBundle);
        return new SecretExtraction(write(protectedRoot), write(secretBundle), !secretBundle.isEmpty());
    }

    /** 只在单次运行时构建边界恢复敏感叶子；持久化层永远保存 protectedConfig。 */
    String restoreSecrets(String protectedConfig, String secretBundleJson) {
        JsonNode protectedRoot = parse(protectedConfig).deepCopy();
        JsonNode bundle = parse(secretBundleJson);
        int[] restored = {0};
        JsonNode resolved = restoreNode(protectedRoot, "", bundle, restored);
        if (restored[0] != bundle.size() || containsSecretRefMarker(resolved)) {
            throw invalid("MCP SecretRef 材料与配置占位符不一致");
        }
        return write(resolved);
    }

    boolean containsInlineSecrets(String config) {
        return extractSecrets(config).hasSecrets();
    }

    private JsonNode extractNode(JsonNode node, boolean protectedPosition, String pointer,
                                 ObjectNode secretBundle) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isValueNode()) {
            if (!protectedPosition) {
                return node;
            }
            if (isPlaceholder(node) || isSecretRefMarker(node)) {
                throw invalid("MCP 敏感字段不能使用内部占位符");
            }
            secretBundle.set(pointer, node.deepCopy());
            return objectMapper.getNodeFactory().textNode(SECRET_REF_MARKER);
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                boolean childProtected = protectedPosition || isHeaders(name) || isSensitiveKey(name);
                object.set(name, extractNode(object.get(name), childProtected,
                    pointer + "/" + escapePointer(name), secretBundle));
            }
            return object;
        }
        ArrayNode array = (ArrayNode) node;
        for (int i = 0; i < array.size(); i++) {
            array.set(i, extractNode(array.get(i), protectedPosition,
                pointer + "/" + i, secretBundle));
        }
        return array;
    }

    private JsonNode restoreNode(JsonNode node, String pointer, JsonNode bundle, int[] restored) {
        if (isSecretRefMarker(node)) {
            JsonNode secret = bundle.get(pointer);
            if (secret == null) {
                throw invalid("MCP SecretRef 缺少路径: " + pointer);
            }
            restored[0]++;
            return secret.deepCopy();
        }
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                object.set(name, restoreNode(object.get(name),
                    pointer + "/" + escapePointer(name), bundle, restored));
            }
            return object;
        }
        ArrayNode array = (ArrayNode) node;
        for (int i = 0; i < array.size(); i++) {
            array.set(i, restoreNode(array.get(i), pointer + "/" + i, bundle, restored));
        }
        return array;
    }

    private String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private TargetIdentity validateTarget(String mcpType, JsonNode root) {
        String normalizedType = normalizeType(mcpType);
        JsonNode target = unwrapMcpServers(root);
        if (McpServerSpec.TYPE_STDIO.equals(normalizedType)) {
            return validateStdioTarget(normalizedType, target);
        }
        return validateRemoteTarget(normalizedType, target);
    }

    private TargetIdentity validateRemoteTarget(String normalizedType, JsonNode target) {
        JsonNode urlNode = target.get("url");
        if (urlNode == null || !urlNode.isTextual() || urlNode.textValue().isBlank()) {
            throw invalid("远程 MCP config.url 不能为空");
        }
        try {
            URI uri = new URI(urlNode.textValue().trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!HTTP_SCHEME.equals(scheme) && !"https".equals(scheme)) {
                throw invalid("远程 MCP URL 仅支持 http/https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw invalid("远程 MCP URL 必须包含合法 host");
            }
            if (uri.getRawUserInfo() != null) {
                throw invalid("远程 MCP URL 禁止携带 userInfo，凭据请放入 headers 或 SecretRef");
            }
            if (uri.getRawQuery() != null) {
                throw invalid("远程 MCP URL 禁止携带 query，凭据请放入 headers 或 SecretRef");
            }
            if (uri.getRawFragment() != null) {
                throw invalid("远程 MCP URL 禁止携带 fragment");
            }
            int effectivePort = uri.getPort() >= 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String endpoint = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + effectivePort + path;
            return new TargetIdentity(normalizedType, endpoint, List.of());
        } catch (URISyntaxException e) {
            throw invalid("远程 MCP URL 不合法");
        }
    }

    private TargetIdentity validateStdioTarget(String normalizedType, JsonNode target) {
        JsonNode commandNode = target.get("command");
        if (commandNode == null || !commandNode.isTextual() || commandNode.textValue().isBlank()) {
            throw invalid("stdio MCP config.command 不能为空");
        }
        List<String> executionTarget = new ArrayList<>();
        executionTarget.add(commandNode.textValue());
        JsonNode argsNode = target.get("args");
        if (argsNode != null && !argsNode.isNull()) {
            if (!argsNode.isArray()) {
                throw invalid("stdio MCP config.args 必须是字符串数组");
            }
            for (JsonNode arg : argsNode) {
                if (!arg.isTextual()) {
                    throw invalid("stdio MCP config.args 必须是字符串数组");
                }
                executionTarget.add(arg.textValue());
            }
        }
        String workingDirectory = null;
        for (String key : WORKING_DIRECTORY_KEYS) {
            JsonNode directory = target.get(key);
            if (directory != null && !directory.isNull() && !directory.isTextual()) {
                throw invalid("stdio MCP 工作目录必须是字符串");
            }
            if (directory != null && !directory.isNull()) {
                if (workingDirectory != null && !workingDirectory.equals(directory.textValue())) {
                    throw invalid("stdio MCP 不能配置相互冲突的工作目录");
                }
                workingDirectory = directory.textValue();
            }
        }
        executionTarget.add("workingDirectory=" + (workingDirectory == null ? "" : workingDirectory));
        return new TargetIdentity(normalizedType, commandNode.textValue(), List.copyOf(executionTarget));
    }

    private JsonNode unwrapMcpServers(JsonNode root) {
        JsonNode servers = root.path(McpServerSpec.MCP_SERVERS_WRAPPER_KEY);
        if (!servers.isObject() || servers.isEmpty()) {
            return root;
        }
        if (servers.size() != 1) {
            throw invalid("mcpServers 只能包含一个服务配置");
        }
        JsonNode target = servers.elements().next();
        if (!target.isObject()) {
            throw invalid("mcpServers 服务配置必须是 JSON object");
        }
        return target;
    }

    private String normalizeType(String mcpType) {
        String normalizedType = mcpType == null ? "" : mcpType.toLowerCase(Locale.ROOT);
        if (!McpServerSpec.TYPE_STDIO.equals(normalizedType)
            && !McpServerSpec.TYPE_HTTP.equals(normalizedType)
            && !McpServerSpec.TYPE_SSE.equals(normalizedType)) {
            throw invalid("mcpType 仅支持 stdio/sse/http");
        }
        return normalizedType;
    }

    private JsonNode mergePlaceholders(JsonNode submitted, JsonNode current, boolean protectedPosition) {
        if (isPlaceholder(submitted)) {
            if (!protectedPosition) {
                throw invalid("脱敏占位符只能用于敏感字段更新");
            }
            if (current == null || current.isMissingNode() || current.isNull() || isPlaceholder(current)) {
                throw invalid("脱敏占位符没有可复用的旧 secret，请重新提供");
            }
            return current.deepCopy();
        }
        if (submitted == null || submitted.isNull()) {
            return submitted;
        }
        if (submitted.isObject()) {
            ObjectNode object = (ObjectNode) submitted;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode currentChild = current != null && current.isObject()
                    ? current.get(field.getKey()) : null;
                boolean childProtected = protectedPosition
                    || isHeaders(field.getKey()) || isSensitiveKey(field.getKey());
                object.set(field.getKey(), mergePlaceholders(field.getValue(), currentChild, childProtected));
            }
            return object;
        }
        if (submitted.isArray()) {
            ArrayNode array = (ArrayNode) submitted;
            for (int i = 0; i < array.size(); i++) {
                JsonNode currentChild = current != null && current.isArray() && i < current.size()
                    ? current.get(i) : null;
                array.set(i, mergePlaceholders(array.get(i), currentChild, protectedPosition));
            }
        }
        return submitted;
    }

    private void redactNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveKey(field.getKey())) {
                    object.put(field.getKey(), SECRET_PLACEHOLDER);
                } else if (isHeaders(field.getKey())) {
                    object.set(field.getKey(), redactAllValues(field.getValue()));
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

    private JsonNode redactAllValues(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                object.set(field.getKey(), redactAllValues(field.getValue()));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, redactAllValues(array.get(i)));
            }
            return array;
        }
        return objectMapper.getNodeFactory().textNode(SECRET_PLACEHOLDER);
    }

    private boolean containsPlaceholder(JsonNode node) {
        if (isPlaceholder(node)) {
            return true;
        }
        if (node == null || node.isValueNode()) {
            return false;
        }
        for (JsonNode child : node) {
            if (containsPlaceholder(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlaceholder(JsonNode node) {
        return node != null && node.isTextual() && SECRET_PLACEHOLDER.equals(node.textValue());
    }

    private boolean containsSecretRefMarker(JsonNode node) {
        if (isSecretRefMarker(node)) {
            return true;
        }
        if (node == null || node.isValueNode()) {
            return false;
        }
        for (JsonNode child : node) {
            if (containsSecretRefMarker(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSecretRefMarker(JsonNode node) {
        return node != null && node.isTextual() && SECRET_REF_MARKER.equals(node.textValue());
    }

    private boolean isHeaders(String fieldName) {
        return "headers".equals(normalize(fieldName));
    }

    private boolean isSensitiveKey(String fieldName) {
        String normalized = normalize(fieldName);
        return normalized.contains("authorization")
            || normalized.endsWith("apikey")
            || "token".equals(normalized)
            || normalized.endsWith("token")
            || "secret".equals(normalized)
            || normalized.endsWith("secret")
            || "password".equals(normalized)
            || normalized.endsWith("password")
            || normalized.contains("credential")
            || normalized.contains("privatekey")
            || normalized.contains("accesskey");
    }

    private String normalize(String fieldName) {
        if (fieldName == null) {
            return "";
        }
        return fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private JsonNode parse(String config) {
        try {
            JsonNode root = objectMapper.readTree(config);
            if (root == null || !root.isObject()) {
                throw invalid("config 必须是 JSON object");
            }
            return root;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("config 不是合法 JSON: " + e.getMessage());
        }
    }

    private String write(JsonNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw invalid("config 无法序列化");
        }
    }

    private BizException invalid(String message) {
        return new BizException(ResultCode.PARAM_INVALID, message);
    }

    private record TargetIdentity(String type, String endpoint, List<String> executionTarget) {
    }

    record SecretExtraction(String protectedConfig, String secretBundleJson, boolean hasSecrets) {
    }
}
