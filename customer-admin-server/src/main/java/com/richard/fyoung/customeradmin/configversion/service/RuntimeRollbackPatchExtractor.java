package com.richard.fyoung.customeradmin.configversion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** 从历史完整快照提取、序列化并校验安全回滚白名单。 */
@Component
public class RuntimeRollbackPatchExtractor {

    private static final String SYSTEM_PROMPT = "systemPrompt";
    private static final String AGENT = "agent";
    private static final String MAX_ITERS = "maxIters";
    private static final Set<String> PATCH_FIELDS = Set.of(SYSTEM_PROMPT, MAX_ITERS);
    /** 与 Agent 保存入口保持同一领域约束，历史异常值不得借回滚绕过。 */
    private static final int MAX_ITERS_MIN = 1;
    private static final int MAX_ITERS_MAX = 100;

    private final ObjectMapper objectMapper;

    public RuntimeRollbackPatchExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 历史快照可以包含任意旧字段，但只提取两项显式白名单。 */
    public RuntimeRollbackPatch extract(String snapshot) {
        JsonNode root = readObject(snapshot, "历史配置快照不是有效 JSON 对象");
        if (!root.has(SYSTEM_PROMPT)) {
            throw invalid("历史配置快照缺少 systemPrompt，无法安全回滚");
        }
        JsonNode agent = root.get(AGENT);
        if (agent == null || !agent.isObject() || !agent.has(MAX_ITERS)) {
            throw invalid("历史配置快照缺少 agent.maxIters，无法安全回滚");
        }
        return values(root.get(SYSTEM_PROMPT), agent.get(MAX_ITERS));
    }

    /** 任务表只保存白名单补丁；即使历史快照含密文，也不会被复制到任务。 */
    public String serialize(RuntimeRollbackPatch patch) {
        if (patch == null) {
            throw invalid("安全回滚补丁不能为空");
        }
        ObjectNode root = objectMapper.createObjectNode();
        if (patch.systemPrompt() == null) {
            root.putNull(SYSTEM_PROMPT);
        } else {
            root.put(SYSTEM_PROMPT, patch.systemPrompt());
        }
        if (patch.maxIters() == null) {
            root.putNull(MAX_ITERS);
        } else {
            root.put(MAX_ITERS, patch.maxIters());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize safe rollback patch", e);
        }
    }

    /** Worker 读取任务补丁时再次 fail-closed 校验字段集合，防止库内任务被扩展成历史快照重放。 */
    public RuntimeRollbackPatch deserialize(String patchJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(patchJson);
        } catch (Exception e) {
            throw new IllegalStateException("safe rollback patch is not valid JSON", e);
        }
        if (root == null || !root.isObject() || !hasExactPatchFields(root)) {
            throw new IllegalStateException("safe rollback patch contains unsupported fields");
        }
        try {
            return values(root.get(SYSTEM_PROMPT), root.get(MAX_ITERS));
        } catch (BizException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** 以实际快照重新计算摘要；库内记录摘要存在时必须完全一致。 */
    public String verifyContentHash(String snapshot, String recordedHash) {
        String actualHash = sha256(snapshot);
        if (StringUtils.hasText(recordedHash) && !actualHash.equalsIgnoreCase(recordedHash.trim())) {
            throw invalid("历史配置快照摘要校验失败，禁止回滚");
        }
        return actualHash;
    }

    private RuntimeRollbackPatch values(JsonNode promptNode, JsonNode maxItersNode) {
        if (promptNode == null || (!promptNode.isNull() && !promptNode.isTextual())) {
            throw invalid("历史 systemPrompt 类型无效");
        }
        if (maxItersNode == null || (!maxItersNode.isNull() && !maxItersNode.isIntegralNumber())) {
            throw invalid("历史 agent.maxIters 类型无效");
        }
        if (!maxItersNode.isNull() && !maxItersNode.canConvertToInt()) {
            throw invalid("历史 agent.maxIters 超出整数范围");
        }
        Integer maxIters = maxItersNode.isNull() ? null : maxItersNode.intValue();
        if (maxIters != null && (maxIters < MAX_ITERS_MIN || maxIters > MAX_ITERS_MAX)) {
            throw invalid("历史 agent.maxIters 必须在 1 到 100 之间");
        }
        return new RuntimeRollbackPatch(promptNode.isNull() ? null : promptNode.textValue(), maxIters);
    }

    private boolean hasExactPatchFields(JsonNode root) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        int count = 0;
        while (fields.hasNext()) {
            if (!PATCH_FIELDS.contains(fields.next().getKey())) {
                return false;
            }
            count++;
        }
        return count == PATCH_FIELDS.size() && root.has(SYSTEM_PROMPT) && root.has(MAX_ITERS);
    }

    private JsonNode readObject(String json, String message) {
        if (!StringUtils.hasText(json)) {
            throw invalid(message);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalid(message);
            }
            return root;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(message);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private BizException invalid(String message) {
        return new BizException(ResultCode.PARAM_INVALID, message);
    }
}
