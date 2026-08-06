package com.richard.fyoung.customerwork.tool.devtool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import lombok.Builder;
import lombok.Getter;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import java.util.Locale;
import java.util.Set;

/**
 * 结构化数据格式互转纯函数集：JSON ⇄ YAML ⇄ XML。
 *
 * <p>典型用途：对接方给的是 XML 报文、Nacos 配置是 YAML，而排查与录入多以 JSON 为准。三种格式
 * 统一先解析成 Jackson 树模型再按目标格式输出，因此任意两种之间都能直转。</p>
 *
 * <p><b>已知语义损耗</b>（工具描述里同样写明，避免调用方误判）：</p>
 * <ul>
 *   <li>XML 无类型系统，转出的 JSON 里数字与布尔都是字符串；</li>
 *   <li>XML 的同名重复子元素在树模型里只保留最后一个，这类"数组型" XML 请勿依赖本工具转换；</li>
 *   <li>YAML 多文档（{@code ---} 分隔）只处理第一个文档；</li>
 *   <li>JSON/YAML 转 XML 需要一个根元素名（默认 root），数组无法直接作为 XML 根。</li>
 * </ul>
 *
 * <p><b>安全</b>：XML 解析显式关闭 DTD 与外部实体，杜绝 XXE（本工具的输入完全来自用户/模型，
 * 是典型的不可信输入）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class DataFormatDevToolOps {

    /** 支持的格式。 */
    private static final String FORMAT_JSON = "JSON";
    private static final String FORMAT_YAML = "YAML";
    private static final String FORMAT_XML = "XML";
    private static final Set<String> SUPPORTED_FORMATS = Set.of(FORMAT_JSON, FORMAT_YAML, FORMAT_XML);

    /** 输入长度上限：512KB，超出多半是传错内容（工具箱面向配置与报文，不是文件转换服务）。 */
    private static final int MAX_INPUT_LENGTH = 512 * 1024;

    /** JSON/YAML 转 XML 时的默认根元素名。 */
    private static final String DEFAULT_ROOT_NAME = "root";

    /** XML 根元素名的合法字符（保守取 NCName 子集，避免生成非法 XML）。 */
    private static final String ROOT_NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_.-]*";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** YAML 输出去掉文档起始符 ---，与主流在线工具的观感一致。 */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

    private static final XmlMapper XML_MAPPER = buildSecureXmlMapper();

    /**
     * 格式互转。
     *
     * @param content      待转换内容
     * @param sourceFormat 源格式：json / yaml / xml
     * @param targetFormat 目标格式：json / yaml / xml
     * @param rootName     转 XML 时的根元素名，null 取 root；其它目标格式忽略
     * @return 转换结果
     */
    public ConvertResult convert(String content, String sourceFormat, String targetFormat, String rootName) {
        DevToolArgs.requireNonBlank(content, "content");
        if (content.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("内容长度超过上限 " + MAX_INPUT_LENGTH + " 字符，当前 " + content.length());
        }
        String source = normalizeFormat(sourceFormat, "sourceFormat");
        String target = normalizeFormat(targetFormat, "targetFormat");

        JsonNode tree = parse(content, source);
        return ConvertResult.builder()
            .sourceFormat(source.toLowerCase(Locale.ROOT))
            .targetFormat(target.toLowerCase(Locale.ROOT))
            .result(write(tree, target, rootName))
            .build();
    }

    /** 按源格式解析成树模型，解析失败带上格式名与原始原因。 */
    private JsonNode parse(String content, String format) {
        try {
            switch (format) {
                case FORMAT_YAML:
                    return YAML_MAPPER.readTree(content);
                case FORMAT_XML:
                    return XML_MAPPER.readTree(content);
                case FORMAT_JSON:
                default:
                    return JSON_MAPPER.readTree(content);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("按 " + format + " 解析失败：" + e.getOriginalMessage(), e);
        }
    }

    /** 按目标格式输出。 */
    private String write(JsonNode tree, String format, String rootName) {
        try {
            switch (format) {
                case FORMAT_YAML:
                    return YAML_MAPPER.writeValueAsString(tree);
                case FORMAT_XML:
                    return writeXml(tree, rootName);
                case FORMAT_JSON:
                default:
                    return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("输出为 " + format + " 失败：" + e.getOriginalMessage(), e);
        }
    }

    /** 输出 XML：数组无法作为 XML 根元素，这里显式拦下并说明，避免生成结构诡异的 XML。 */
    private String writeXml(JsonNode tree, String rootName) throws JsonProcessingException {
        if (tree.isArray()) {
            throw new IllegalArgumentException("数组不能直接作为 XML 根元素，请先把数组包进一个对象再转换");
        }
        return XML_MAPPER.writer().withRootName(resolveRootName(rootName)).writeValueAsString(tree);
    }

    /** 根元素名校验：非法字符会生成不合法的 XML，入口直接挡掉。 */
    private String resolveRootName(String rootName) {
        if (rootName == null || rootName.trim().isEmpty()) {
            return DEFAULT_ROOT_NAME;
        }
        String trimmed = rootName.trim();
        if (!trimmed.matches(ROOT_NAME_PATTERN)) {
            throw new IllegalArgumentException("rootName 不是合法的 XML 元素名（须以字母或下划线开头，"
                + "后续只能是字母、数字、下划线、点、连字符）：" + rootName);
        }
        return trimmed;
    }

    /** 规范化格式名。 */
    private String normalizeFormat(String format, String fieldName) {
        DevToolArgs.requireNonBlank(format, fieldName);
        String upper = format.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_FORMATS.contains(upper)) {
            throw new IllegalArgumentException(fieldName + " 仅支持 json/yaml/xml，当前 " + format);
        }
        return upper;
    }

    /** 构造禁用 DTD 与外部实体的 XmlMapper（防 XXE）。 */
    private static XmlMapper buildSecureXmlMapper() {
        XMLInputFactory inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return new XmlMapper(new XmlFactory(inputFactory, XMLOutputFactory.newFactory()));
    }

    /**
     * 转换结果。
     */
    @Getter
    @Builder
    public static class ConvertResult {
        /** 源格式（小写）。 */
        private final String sourceFormat;
        /** 目标格式（小写）。 */
        private final String targetFormat;
        /** 转换后的内容。 */
        private final String result;
    }
}
