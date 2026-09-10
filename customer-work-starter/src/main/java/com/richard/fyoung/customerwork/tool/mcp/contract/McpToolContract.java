package com.richard.fyoung.customerwork.tool.mcp.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一次 MCP 服务器工具契约的快照：规范化内容 + 指纹。
 *
 * <h3>为什么需要它</h3>
 * <p>MCP 工具的定义住在<b>远端服务器</b>，随时可能变：加一个必填参数、改一个字段类型、
 * 下线一个工具。而项目对模型、提示词、路由都建了完整的版本治理（不可变版本、内容指纹、上线认证），
 * <b>唯独 MCP 没有任何基线</b>——`listTools` 的结果只拿去前端展示，不落库，
 * 于是「今天和昨天是不是同一套工具」没人回答得了。</p>
 *
 * <h3>规范化不是可选的</h3>
 * <p>指纹的前提是同一份契约必须算出同一个值。MCP 返回的 {@code properties} 是
 * {@code Map}、{@code required} 是 {@code List}，两者的<b>顺序在语义上都无关</b>，
 * 而远端实现完全可能每次给出不同顺序（Go 的 map 迭代顺序就是随机的）。
 * 不规范化就会天天报「漂移」，几次之后这个能力就没人信了——本仓库对
 * 「每跑必红的门禁」有过明确教训。</p>
 *
 * <p>因此：工具按名排序、properties 递归按 key 排序、required 排序去重，再算 SHA-256。</p>
 *
 * @param hash          规范化内容的 SHA-256（64 位十六进制）
 * @param toolCount     工具数量
 * @param canonicalJson 规范化后的完整契约，供落库与 diff 展示
 * @param tools         按工具名索引的规范化视图，供逐工具比对
 * @author owlzhangfq@gmail.com
 */
public record McpToolContract(String hash,
                              int toolCount,
                              String canonicalJson,
                              Map<String, McpToolContract.ToolView> tools) {

    /**
     * 单个工具的规范化视图。
     *
     * @param name        工具名
     * @param description 工具描述
     * @param schemaType  入参 schema 顶层类型
     * @param properties  字段名 → 该字段的类型（取 JSON Schema 片段里的 {@code type}；取不到记 {@code unknown}）
     * @param required    必填字段名（已排序）
     */
    public record ToolView(String name,
                           String description,
                           String schemaType,
                           Map<String, String> properties,
                           List<String> required) {
    }

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** JSON Schema 片段里取不到 {@code type} 时的占位。 */
    static final String UNKNOWN_TYPE = "unknown";

    /** 空契约：服务器一个工具都没暴露，或采集失败前的初值。 */
    public static McpToolContract empty() {
        return of(List.of());
    }

    /** 从一次 {@code listTools} 的结果构造快照。 */
    public static McpToolContract of(List<McpToolDescriptor> descriptors) {
        List<McpToolDescriptor> source = descriptors == null ? List.of() : descriptors;
        Map<String, ToolView> views = new TreeMap<>();
        for (McpToolDescriptor descriptor : source) {
            if (descriptor == null || descriptor.name() == null || descriptor.name().isBlank()) {
                continue;
            }
            views.put(descriptor.name(), toView(descriptor));
        }
        String canonical = serialize(views);
        return new McpToolContract(sha256(canonical), views.size(), canonical, Map.copyOf(views));
    }

    private static ToolView toView(McpToolDescriptor descriptor) {
        Map<String, String> properties = new TreeMap<>();
        Map<String, Object> raw = descriptor.properties();
        if (raw != null) {
            raw.forEach((field, schema) -> properties.put(field, typeOf(schema)));
        }
        List<String> required = new ArrayList<>(
            descriptor.required() == null ? List.of() : descriptor.required());
        required.removeIf(Objects::isNull);
        required.sort(String::compareTo);
        return new ToolView(descriptor.name(), descriptor.description(),
            descriptor.schemaType(), Map.copyOf(properties), List.copyOf(required));
    }

    /**
     * 从一段 JSON Schema 片段里取字段类型。
     *
     * <p>只取 {@code type} 而不是整段 schema，是刻意的取舍：片段里还有 description /
     * examples / default，整段进来会让上游随手补一句字段说明都变成一次漂移事件。
     * 代价是<b>字段级的说明改了这里看不见</b>——要覆盖它得先回答「schema 片段里哪些键算契约」，
     * 那是独立的一件事。</p>
     *
     * <p>注意与<b>工具级</b> description 的区别：那一个是参与指纹的（见 {@link ToolView}）。
     * 不参与的话，只改工具描述的那次变更会被 {@code McpContractDrift#between} 的
     * 「指纹相同直接判无漂移」快路径拦下，{@code DESCRIPTION_CHANGED} 就成了永远报不出来的死代码。</p>
     */
    private static String typeOf(Object schemaFragment) {
        if (schemaFragment instanceof Map<?, ?> fragment) {
            Object type = fragment.get("type");
            if (type != null) {
                return String.valueOf(type);
            }
        }
        return UNKNOWN_TYPE;
    }

    private static String serialize(Map<String, ToolView> views) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(new LinkedHashMap<>(views));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mcp tool contract serialization failed", e);
        }
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
