package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.List;

/**
 * 语义缓存配置。
 *
 * <p><b>默认关闭</b>，这是刻意的：语义缓存在客服场景用错了会造成数据泄露
 * （把 A 用户的订单信息返回给 B），开启前必须先确认 {@link #cacheableIntents} 白名单里
 * 的每个意图都满足"两个不同用户问同一句话，答案必然相同"。</p>
 */
@Data
public class SemanticCacheProperties {

    /** 总开关，默认关。开启前先读 {@code SemanticCacheService} 的类注释。 */
    private boolean enabled = false;

    /** 存储模式：memory（进程内，多副本下命中率被实例数除掉）| jdbc（落 cw_semantic_cache，共享）。 */
    private String storeMode = "memory";

    /**
     * 命中阈值（余弦相似度，0-1）。
     *
     * <p>0.95 是保守取值：客服问答里"怎么退货"和"怎么换货"的向量相似度能到 0.9 上下，
     * 阈值定低了会把换货答成退货。宁可少命中，不可答错。</p>
     */
    private double similarityThreshold = 0.95d;

    /**
     * 可缓存的意图白名单。
     *
     * <p>默认只放行 {@code consult}（政策咨询：运费、发票、退换货规则——对所有人答案相同）。
     * {@code order}/{@code refund} 天然依赖个人数据，{@code complaint} 需要个性化安抚，都不该缓存。</p>
     */
    private List<String> cacheableIntents = List.of("consult");

    /** 条目存活秒数，默认 7 天；<=0 表示不过期。政策会变，缓存不该永久有效。 */
    private long ttlSeconds = 7 * 24 * 3600L;

    /**
     * 单租户缓存条目上限，超出后淘汰最久未命中的。
     *
     * <p>MySQL 8.0 无原生向量索引、相似度在应用层逐条算，条目无上限增长会让查缓存比调模型还慢。</p>
     */
    private int maxSize = 2000;

    /** 单次相似度比对的候选条数上限，与 {@link #maxSize} 一起兜住查询开销。 */
    private int maxCandidates = 200;

    /** 问题最短长度：太短的（"嗯""在吗"）没有缓存价值，且极易误命中。 */
    private int minQuestionLength = 4;

    /** 问题最长长度：长问题几乎不会重复，缓存它只是白占容量。 */
    private int maxQuestionLength = 200;

    /**
     * Embedding 端点 base-url。
     *
     * <p>与对话模型的 {@code model.base-url} 分开配：DashScope 的 embeddings 是原生路径，
     * 对话那边常填 OpenAI 兼容端点（{@code /compatible-mode/v1}），拿来发 embedding 请求路径对不上。
     * 与 admin 侧知识库的取值口径一致。</p>
     */
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com";

    /** 向量维度（text-embedding-v3 支持 1024/768/512 等）。 */
    private int embeddingDimensions = 1024;

    /** 单次 embedding 请求的最大文本条数。语义缓存每次只算一条问题，此值仅为接口契约所需。 */
    private int embeddingBatchSize = 10;
}
