package com.richard.fyoung.customerwork.data.rag.search;

import java.math.BigDecimal;

/**
 * 一次检索所需的知识库连接参数（apiKey 已解密）。刻意与任何持久化实体解耦：
 * 检索客户端只依赖这几个值，既不碰 MyBatis 实体，也让单测能脱离数据库直接构造。
 *
 * @param id             知识库 ID（日志定位用）
 * @param kbName         知识库名称（注入内容里标注来源用）
 * @param baseUrl        RAG 服务基址
 * @param appId          知识库应用 ID
 * @param apiKey         AppKey 明文
 * @param contentType    请求 Content-Type
 * @param extraHeaders   自定义请求头（JSON 对象字符串，空=无）
 * @param topN           单库返回条数
 * @param scoreThreshold 相关度阈值（低于该值丢弃；0=不过滤）
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeBaseEndpoint(Long id, String kbName, String baseUrl, String appId, String apiKey,
                                    String contentType, String extraHeaders, Integer topN,
                                    BigDecimal scoreThreshold) {

    /** 默认返回条数（配置留空时用）。 */
    public static final int DEFAULT_TOP_N = 5;
    /** 默认 Content-Type（配置留空时用）。 */
    public static final String DEFAULT_CONTENT_TYPE = "application/json";

    public int effectiveTopN() {
        return topN == null || topN <= 0 ? DEFAULT_TOP_N : topN;
    }

    public String effectiveContentType() {
        return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
    }

    /** 阈值为空视为 0（不过滤）。 */
    public BigDecimal effectiveScoreThreshold() {
        return scoreThreshold == null ? BigDecimal.ZERO : scoreThreshold;
    }
}
