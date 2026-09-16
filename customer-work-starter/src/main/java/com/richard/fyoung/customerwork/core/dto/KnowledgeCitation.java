package com.richard.fyoung.customerwork.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条知识引用：这次回答用到了哪一段知识。
 *
 * <p><b>为什么要回传</b>：此前召回的知识只进模型上下文，用户看不到答案出处，
 * 运营也无法核查"是哪一条知识把模型带偏了"。知识库改错一句话，
 * 表现是客服开始给出错误答复，而没有任何线索指向那条知识——
 * 只能靠人把知识库通读一遍去猜。</p>
 *
 * <p>不含正文：引用是溯源线索而不是内容副本，正文在知识库里有权威版本，
 * 复制一份到响应里既撑大报文，又会在知识更新后变成对不上的旧内容。</p>
 *
 * <p>实际 RAG 元数据由 KnowledgeSourceTrackingTools 在框架格式化前采集，采集器通过本轮
 * RuntimeContext 显式传递，避免内置工具的阻塞订阅丢失 Reactor Context。
 * 文本标记保留给模型说明出处及兼容旧工具；解析结果仅是线索，不构造内部文档引用，
 * 也不能作为原文访问的授权依据。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Schema(description = "知识引用（回答的来源线索）")
public record KnowledgeCitation(
        @Schema(description = "知识库名称", example = "售后FAQ") String knowledgeBase,
        @Schema(description = "来源文档标识", example = "doc-refund-policy") String documentId,
        @Schema(description = "分片标识，供运营精确定位到段", example = "10237") String chunkId,
        @Schema(description = "相似度分数，越大越相近", example = "0.87") Double score) {

    /** 标记行的行首前缀，解析时据此认出该行。 */
    private static final String PREFIX = "【知识来源】";

    /** 字段分隔符。 */
    private static final String SEPARATOR = " | ";

    /** 分片标识在标记里带的记号，便于人一眼看出那是段号而不是又一个名称。 */
    private static final String CHUNK_MARK = "#";

    /**
     * 字段值里的 {@code |} 一律换成全角，避免运营填的知识库名把分隔符撑破。
     *
     * <p>用替换而不是转义符：这一行是要给模型和用户看的，反斜杠转义会让它变成技术噪音，
     * 而全角竖线在中文语境里本就是常见写法，读起来没有区别。</p>
     */
    private static final char RAW_PIPE = '|';
    private static final char SAFE_PIPE = '｜';

    /** 分数保留两位：更细的精度对读者没有意义，还会让标记行变长。 */
    private static final String SCORE_FORMAT = "%.2f";

    /**
     * 生成放在知识正文首行的来源标记。
     *
     * <p>与 {@link #parseAll(String)} 是同一份格式的两端，改其一必须改另一端——
     * 因此两者刻意放在同一个类里，并由往返测试钉住。</p>
     */
    public String marker() {
        StringBuilder line = new StringBuilder(PREFIX)
            .append(escape(knowledgeBase))
            .append(SEPARATOR).append(escape(documentId))
            .append(SEPARATOR).append(CHUNK_MARK).append(escape(chunkId));
        if (score != null) {
            line.append(SEPARATOR).append(String.format(SCORE_FORMAT, score));
        }
        return line.toString();
    }

    /**
     * 从任意文本里解析出全部来源标记（逐行扫描，认不出的行原样跳过）。
     *
     * <p>解析失败一律跳过而不抛错：这条链路上游是模型生成的文本，
     * 它可能把标记复述得缺胳膊少腿，为此打断一轮对话是不成比例的。</p>
     */
    public static List<KnowledgeCitation> parseAll(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<KnowledgeCitation> found = new ArrayList<>();
        for (String line : text.split("\\R")) {
            KnowledgeCitation citation = parseLine(line);
            if (citation != null) {
                found.add(citation);
            }
        }
        return List.copyOf(found);
    }

    private static KnowledgeCitation parseLine(String line) {
        if (line == null) {
            return null;
        }
        int start = line.indexOf(PREFIX);
        if (start < 0) {
            return null;
        }
        String[] parts = line.substring(start + PREFIX.length()).split("\\s\\|\\s", -1);
        if (parts.length < 3) {
            return null;
        }
        String chunkId = parts[2].trim();
        if (chunkId.startsWith(CHUNK_MARK)) {
            chunkId = chunkId.substring(CHUNK_MARK.length());
        }
        if (chunkId.isEmpty()) {
            return null;
        }
        return new KnowledgeCitation(parts[0].trim(), parts[1].trim(), chunkId,
            parts.length >= 4 ? parseScore(parts[3]) : null);
    }

    private static Double parseScore(String raw) {
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException ignored) {
            // 分数只是展示辅助，解析不出就当没有，不影响引用本身可用
            return null;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace(RAW_PIPE, SAFE_PIPE);
    }
}
