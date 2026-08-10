package com.richard.fyoung.customerwork.devtool;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 处理纯函数集：格式化 / 压缩 / 校验（行列号定位）/ 转义 / 去转义 / Unicode 解码。
 *
 * <p>无 Spring 依赖、无状态。参数非法在方法入口 fast-fail 抛 {@link IllegalArgumentException}，
 * 内部不再层层判空。JSON 解析错误统一带上 Jackson 提供的行列号，便于 LLM 自我纠正。</p>
 *
 * <p>转义/去转义/Unicode 解码三项与管理台"开发者工具箱 → JSON 工具"页面同语义（页面侧用
 * JSON.stringify/JSON.parse 实现），两端对同一输入必须给出相同结果。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class JsonDevToolOps {

    /** 复用同一 ObjectMapper（线程安全，读/写皆无状态使用）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 去转义专用的严格 Mapper：必须开启 FAIL_ON_TRAILING_TOKENS。
     *
     * <p>Jackson 默认只读第一个 token 就返回，尾部残留内容一概不管——补引号后的
     * {@code "{"a":1}"} 会被静默解析成字符串 {@code "{"} 而不报错，与页面版 {@code JSON.parse}
     * 直接抛 SyntaxError 的行为不一致。两端语义必须对齐，否则同一段输入在页面报错、在智能体
     * 侧却悄悄返回半截内容。</p>
     */
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 允许的缩进宽度：2 或 4 个空格。 */
    private static final int INDENT_TWO = 2;
    private static final int INDENT_FOUR = 4;

    /** 校验结果中"不适用"的行列号占位值（合法 JSON 时无行列可指）。 */
    private static final int LOCATION_NOT_APPLICABLE = -1;

    /** Unicode 转义序列 \\uXXXX。 */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    /** 十六进制基数。 */
    private static final int HEX_RADIX = 16;

    /** 判定"已带外层双引号"所需的最短长度（一对引号本身就占 2 个字符）。 */
    private static final int WRAPPED_MIN_LENGTH = 2;

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

    /**
     * 转义：把任意原文变成"可安全嵌入 JSON 的字符串字面量"，含外层双引号。
     *
     * <p>语义与页面版的 {@code JSON.stringify(raw)} 一致：自动处理引号、反斜杠与控制字符，
     * 非 ASCII（如中文）保持原样、不转成 \\uXXXX。</p>
     *
     * @param text 原文（允许空串）
     * @return 带外层双引号的转义串
     */
    public String escape(String text) {
        DevToolArgs.requireNonNull(text, "text");
        try {
            return MAPPER.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            // 纯 String 序列化不会失败，仅作兜底
            throw new IllegalArgumentException("转义失败：" + e.getOriginalMessage(), e);
        }
    }

    /**
     * 去转义：把转义串还原成原文。
     *
     * <p>语义与页面版一致：外层双引号可有可无——没有时视为"转义串本体"，补上引号后按 JSON
     * 字符串语法解析。解析结果不是字符串（如传入的是对象/数组）同样视为输入非法。</p>
     *
     * @param text 转义串（带或不带外层双引号）
     * @return 还原后的原文
     */
    public String unescape(String text) {
        DevToolArgs.requireNonNull(text, "text");
        String trimmed = text.trim();
        boolean alreadyWrapped = trimmed.length() >= WRAPPED_MIN_LENGTH
            && trimmed.startsWith("\"") && trimmed.endsWith("\"");
        String candidate = alreadyWrapped ? trimmed : "\"" + trimmed + "\"";
        JsonNode node;
        try {
            node = STRICT_MAPPER.readTree(candidate);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                "内容不是合法的转义字符串（无法按 JSON 字符串语法解析，检查引号/反斜杠是否匹配）："
                    + e.getOriginalMessage(), e);
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("内容不是转义字符串（解析结果是 "
                + node.getNodeType().name().toLowerCase(Locale.ROOT) + " 而非字符串）");
        }
        return node.asText();
    }

    /**
     * 把文本里的 Unicode 转义序列（\\uXXXX）解码成真实字符（常见于日志里被转义的中文），
     * 其余内容原样保留。与页面版一致：代理对由两个连续的 \\uXXXX 表示，逐个解码后自然拼回完整字符。
     *
     * @param text 含 \\uXXXX 的文本（允许空串、允许不含转义序列）
     * @return 解码后的文本
     */
    public String decodeUnicode(String text) {
        DevToolArgs.requireNonNull(text, "text");
        Matcher matcher = UNICODE_ESCAPE.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            sb.append(text, cursor, matcher.start());
            sb.append((char) Integer.parseInt(matcher.group(1), HEX_RADIX));
            cursor = matcher.end();
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
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
