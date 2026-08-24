package com.richard.fyoung.customerwork.tool.mcp;

import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** MCP 工具可调用主体策略的唯一解析与序列化入口。 */
public final class McpSubjectPolicy {

    /** 存量 MCP 的兼容默认值：只允许三类已认证主体，匿名 IP 必须由管理员显式放开。 */
    public static final Set<QuotaSubjectType> DEFAULT_AUTHENTICATED = Set.of(
        QuotaSubjectType.USER, QuotaSubjectType.ADMIN_USER, QuotaSubjectType.API_KEY);

    private McpSubjectPolicy() {
    }

    /** 管理端创建默认最小权限：仅创建它的后台用户链路可调用。 */
    public static List<String> adminCreateDefault() {
        return List.of(QuotaSubjectType.ADMIN_USER.name());
    }

    public static Set<QuotaSubjectType> parse(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_AUTHENTICATED;
        }
        EnumSet<QuotaSubjectType> result = EnumSet.noneOf(QuotaSubjectType.class);
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            result.add(parseOne(value));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("allowed subject types must not be empty");
        }
        return Set.copyOf(result);
    }

    public static Set<QuotaSubjectType> parseStored(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_AUTHENTICATED;
        }
        return parse(Arrays.asList(raw.split(",")));
    }

    public static String serialize(Collection<String> raw) {
        return parse(raw).stream()
            .sorted()
            .map(Enum::name)
            .collect(Collectors.joining(","));
    }

    public static List<String> toNames(String stored) {
        return parseStored(stored).stream().sorted().map(Enum::name).toList();
    }

    private static QuotaSubjectType parseOne(String raw) {
        try {
            return QuotaSubjectType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported subject type: " + raw, e);
        }
    }
}
