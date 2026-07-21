package com.richard.fyoung.customerwork.tool.devtool;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

/**
 * JSON 处理纯函数集：格式化 / 压缩 / 校验（行列号定位）。
 *
 * <p>无 Spring 依赖、无状态。参数非法在方法入口 fast-fail 抛 {@link IllegalArgumentException}，
 * 内部不再层层判空。JSON 解析错误统一带上 Jackson 提供的行列号，便于 LLM 自我纠正。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class JsonDevToolOps {

    /** 复用同一 ObjectMapper（线程安全，读/写皆无状态使用）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 允许的缩进宽度：2 或 4 个空格。 */
    private static final int INDENT_TWO = 2;
    private static final int INDENT_FOUR = 4;

    /** 校验结果中"不适用"的行列号占位值（合法 JSON 时无行列可指）。 */
    private static final int LOCATION_NOT_APPLICABLE = -1;

    /**
     * 美化 JSON：按指定缩进（2 或 4 个空格）重排。
     *
     * @param json   原始 JSON 文本
     * @param indent 缩进宽度，仅支持 2 或 4
     * @return 格式化后的 JSON 文本
     */
    public String format(String json, int indent) {
        DevToolArgs.requireNonBlank(json, "json");
        if (indent != INDENT_TWO && indent != INDENT_FOUR) {
            throw new IllegalArgumentException("indent 仅支持 2 或 4，当前为 " + indent);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return MAPPER.writer(buildPrinter(indent)).writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + describeLocation(e), e);
        }
    }

    /**
     * 压缩 JSON：去除所有多余空白，输出最紧凑形式。
     *
     * @param json 原始 JSON 文本
     * @return 压缩后的 JSON 文本
     */
    public String minify(String json) {
        DevToolArgs.requireNonBlank(json, "json");
        try {
            return MAPPER.writeValueAsString(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + describeLocation(e), e);
        }
    }

    /**
     * 校验 JSON 合法性：合法返回 valid=true；非法不抛异常，而是返回带错误信息与行列号的结果对象。
     *
     * @param json 待校验 JSON 文本（null/空白仍视为参数非法直接抛出）
     * @return 校验结果
     */
    public ValidationResult validate(String json) {
        DevToolArgs.requireNonBlank(json, "json");
        try {
            MAPPER.readTree(json);
            return ValidationResult.builder()
                .valid(true)
                .line(LOCATION_NOT_APPLICABLE)
                .column(LOCATION_NOT_APPLICABLE)
                .build();
        } catch (JsonProcessingException e) {
            JsonLocation location = e.getLocation();
            return ValidationResult.builder()
                .valid(false)
                .errorMessage(e.getOriginalMessage())
                .line(location == null ? LOCATION_NOT_APPLICABLE : location.getLineNr())
                .column(location == null ? LOCATION_NOT_APPLICABLE : location.getColumnNr())
                .build();
        }
    }

    /** 构造指定空格数的缩进 PrettyPrinter（对象与数组一致缩进）。 */
    private DefaultPrettyPrinter buildPrinter(int indent) {
        StringBuilder unit = new StringBuilder();
        for (int i = 0; i < indent; i++) {
            unit.append(' ');
        }
        DefaultIndenter indenter = new DefaultIndenter(unit.toString(), DefaultIndenter.SYS_LF);
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    /** 拼装"错误原文 + 行列号"的定位描述。 */
    private String describeLocation(JsonProcessingException e) {
        JsonLocation location = e.getLocation();
        if (location == null) {
            return e.getOriginalMessage();
        }
        return e.getOriginalMessage() + " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }

    /**
     * JSON 校验结果。valid=true 时 line/column 为 -1（不适用）。
     */
    @Getter
    @Builder
    public static class ValidationResult {
        /** 是否合法。 */
        private final boolean valid;
        /** 错误原因（合法时为 null）。 */
        private final String errorMessage;
        /** 出错行号（1 起，合法时为 -1）。 */
        private final int line;
        /** 出错列号（1 起，合法时为 -1）。 */
        private final int column;
    }
}
