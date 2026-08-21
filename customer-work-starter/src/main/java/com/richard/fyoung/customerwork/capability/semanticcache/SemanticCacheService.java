package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.data.knowledge.VectorMath;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.infra.config.properties.SemanticCacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 语义缓存：问题向量相似度超过阈值就直接返回上次的答案，省掉一次完整的模型调用。
 *
 * <p>客服场景问题重复率极高（"怎么退货"一天可能被问几百次），此前每一次都完整打一遍模型。
 * 命中判定用向量相似度而非字符串相等——"怎么退货"和"退货流程是什么"是同一个问题，
 * 按字面比对永远命中不了。</p>
 *
 * <h3>为什么默认关闭，以及为什么必须有白名单</h3>
 *
 * <p>在客服场景无差别缓存是<b>会出数据泄露事故</b>的：两个用户都问"我的订单到哪了"，
 * 字面与语义都高度相似，但正确答案完全不同——把 A 的物流信息返回给 B，这不是体验问题而是事故。
 * 因此本类只缓存<b>与个人上下文无关的通用问答</b>，判定收口在 {@link #cacheable} 一处：</p>
 *
 * <ol>
 *   <li><b>意图白名单</b>：默认只放行 {@code consult}（政策咨询类）。查订单、退款这类天然带个人数据的意图一律不缓存；</li>
 *   <li><b>个人标识过滤</b>：问题或答案里出现 6 位以上连续数字（订单号 / 手机号 / 单据号）即跳过——
 *       这类问答必然是针对某个人的；</li>
 *   <li><b>双层隔离</b>：表上的 {@code tenant_id} 由租户拦截器自动改写（跨租户不可见），
 *       条目上的 {@code scopeId} 再按业务分区隔一层——不同租户的政策口径本就不同。</li>
 * </ol>
 *
 * <p>过滤在问题侧与答案侧各做一次：意图分类可能判错，而答案里带单号是"这条回答依赖个人数据"
 * 的直接证据，比意图更可信。</p>
 * @author owlzhangfq@gmail.com
 */
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    /**
     * 个人标识特征：6 位以上连续数字。
     *
     * <p>订单号、手机号、快递单号、身份证都落在这个范围内；而"7天无理由""满99包邮"这类
     * 政策数字都在 5 位以内，不会被误伤。</p>
     */
    private static final Pattern PERSONAL_IDENTIFIER = Pattern.compile("\\d{6,}");

    /** 向量序列化分隔符（与 admin 侧知识库向量列同一手法）。 */
    private static final String VECTOR_DELIMITER = ",";

    private final SemanticCacheStore store;

    /** 可空：未配置 Embedding（缺 API Key）时整个缓存自动失效——没有向量就谈不上语义命中。 */
    private final EmbeddingClient embeddingClient;
    private final MultiAgentOrchestrator orchestrator;
    private final TenantResolver tenantResolver;
    private final SemanticCacheProperties properties;

    public SemanticCacheService(SemanticCacheStore store,
                                EmbeddingClient embeddingClient,
                                MultiAgentOrchestrator orchestrator,
                                TenantResolver tenantResolver,
                                SemanticCacheProperties properties) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.orchestrator = orchestrator;
        this.tenantResolver = tenantResolver;
        this.properties = properties;
    }

    /**
     * 查缓存。
     *
     * <p>任何一步失败都返回空（视为未命中）而不是抛出：缓存是加速手段，
     * 它的故障不该让用户问不了问题。</p>
     *
     * @return 命中则返回上次的答案
     */
    public Optional<String> lookup(String sessionId, String question) {
        if (!active()) {
            return Optional.empty();
        }
        try {
            String intent = resolveIntent(question);
            if (!cacheable(question, intent)) {
                return Optional.empty();
            }
            String scopeId = tenantResolver.resolveDataScope(sessionId);
            long now = System.currentTimeMillis();
            List<SemanticCacheEntry> candidates = store.findCandidates(scopeId, intent,
                notBefore(now), properties.getMaxCandidates());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            float[] queryVector = embeddingClient.embedQuery(question);
            SemanticCacheEntry best = null;
            double bestScore = 0.0d;
            for (SemanticCacheEntry candidate : candidates) {
                double score = VectorMath.cosine(queryVector, parseVector(candidate.questionVector()));
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null || bestScore < properties.getSimilarityThreshold()) {
                return Optional.empty();
            }
            store.recordHit(best.id(), now);
            log.info("semantic cache hit: scopeId={}, intent={}, score={}, cachedQuestion={}",
                scopeId, intent, String.format("%.4f", bestScore), best.question());
            return Optional.of(best.answer());
        } catch (Exception e) {
            log.error("semantic cache lookup failed, errorCode={}, sessionId={}",
                "SEMCACHE-LOOKUP-FAIL", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * 写缓存。
     *
     * <p>同样吞掉异常：写缓存失败只是下次还得再问一遍模型，不该影响本次已经答好的回复。</p>
     */
    public void put(String sessionId, String question, String answer) {
        if (!active() || !StringUtils.hasText(answer)) {
            return;
        }
        try {
            String intent = resolveIntent(question);
            if (!cacheable(question, intent) || containsPersonalIdentifier(answer)) {
                return;
            }
            String scopeId = tenantResolver.resolveDataScope(sessionId);
            long now = System.currentTimeMillis();
            float[] vector = embeddingClient.embedQuery(question);
            store.save(SemanticCacheEntry.of(scopeId, intent, question, formatVector(vector), answer, now));
            enforceCapacity(scopeId);
        } catch (Exception e) {
            log.error("semantic cache put failed, errorCode={}, sessionId={}",
                "SEMCACHE-PUT-FAIL", sessionId, e);
        }
    }

    /** 清空某分区缓存：知识库或提示词改过之后，旧答案不再可信。 */
    public int invalidate(String scopeId) {
        int removed = store.clear(scopeId);
        log.info("semantic cache invalidated: scopeId={}, removed={}", scopeId, removed);
        return removed;
    }

    /** 运营视角列出条目（按命中次数降序）：看清楚到底缓存了什么、哪些真的在被复用。 */
    public List<SemanticCacheEntry> list(String scopeId, int limit) {
        return store.listByHits(scopeId, limit);
    }

    /** 定点删除单条：发现某条答得不对时不必清空整个分区。 */
    public boolean evict(Long id) {
        boolean removed = store.remove(id);
        log.info("semantic cache entry evicted: id={}, removed={}", id, removed);
        return removed;
    }

    /** 缓存是否真的可用：开关打开且 Embedding 可用。缺任一条都静默失效，不报错。 */
    private boolean active() {
        return properties.isEnabled() && embeddingClient != null;
    }

    /**
     * 可否缓存——安全判定唯一收口处。
     *
     * <p>三道闸门见类注释。放宽任何一道之前请先想清楚："两个不同用户问同一句话，
     * 答案是否必然相同？"只要答案是否定的，就不能缓存。</p>
     */
    boolean cacheable(String question, String intent) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(intent)) {
            return false;
        }
        if (!properties.getCacheableIntents().contains(intent)) {
            return false;
        }
        int length = question.trim().length();
        if (length < properties.getMinQuestionLength() || length > properties.getMaxQuestionLength()) {
            return false;
        }
        return !containsPersonalIdentifier(question);
    }

    /** 含 6 位以上连续数字即视为带个人标识（订单号/手机号/单据号）。 */
    boolean containsPersonalIdentifier(String text) {
        return StringUtils.hasText(text) && PERSONAL_IDENTIFIER.matcher(text).find();
    }

    /**
     * 取意图：只用规则快车道，零模型成本。
     *
     * <p>快车道判不出来的（模糊、多意图）一律不缓存——判不清意图就更判不清"这个答案能不能复用"。</p>
     */
    private String resolveIntent(String question) {
        return orchestrator.fastRouteIntent(question).orElse(null);
    }

    /** TTL 下界时间戳；ttl<=0 表示不过期。 */
    private long notBefore(long nowMs) {
        long ttlMs = properties.getTtlSeconds() * 1000L;
        return ttlMs > 0 ? nowMs - ttlMs : 0L;
    }

    /** 超出容量上限时淘汰最久未命中的条目——候选集无上限增长会让查缓存比调模型还慢。 */
    private void enforceCapacity(String scopeId) {
        int maxSize = properties.getMaxSize();
        if (maxSize <= 0 || store.count(scopeId) <= maxSize) {
            return;
        }
        int removed = store.evictLeastRecentlyUsed(scopeId, maxSize);
        log.info("semantic cache evicted: scopeId={}, removed={}, keepSize={}", scopeId, removed, maxSize);
    }

    private String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(VECTOR_DELIMITER);
            }
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private float[] parseVector(String text) {
        String[] parts = text.split(VECTOR_DELIMITER);
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
