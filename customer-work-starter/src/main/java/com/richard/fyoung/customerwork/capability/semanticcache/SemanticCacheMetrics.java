package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.core.constant.MetricTags;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 语义缓存的可观测埋点。
 *
 * <h3>为什么先做这个</h3>
 * <p>这套缓存此前<b>一个指标都没有</b>——命中时打一行 info，未命中什么都没有。
 * 于是「命中率多少」「候选集有没有被截断」「查一次缓存要多久」全都没人知道，
 * 而这三件事恰恰决定了下一步该不该动它。</p>
 *
 * <p>报告 P1-13 建议把召回从「按最近命中时间取前 N 条」改成真正的向量检索，
 * 理由是新条目会冷启动饿死。<b>那个理由经实测不成立</b>：新条目写入时
 * {@code lastHitAtMs} 就等于创建时刻，排在候选集最前面。真实代价是另一件事——
 * {@code maxSize=2000} 而 {@code maxCandidates=200}，<b>单次查询只看得到一成缓存</b>。</p>
 *
 * <p>但把它改成向量检索要迁移存储格式（JSON 文本 → 定长二进制）并回填历史数据，
 * 而收益在 {@code similarityThreshold=0.95} 这么高的阈值下并不确定——
 * 扩大候选集能多命中多少，取决于「差一点就命中」的查询有多少。
 * 这几个指标就是用来回答它的：</p>
 * <ul>
 *   <li>{@code truncated} 一直是 0 → 候选根本没触过顶，扩大候选毫无意义；</li>
 *   <li>未命中时的 {@code bestScore} 大量落在阈值下沿 → 阈值太严，调阈值比改存储便宜得多；</li>
 *   <li>{@code bestScore} 普遍很低 → 问题本身就不重复，缓存这条路的天花板就在这里。</li>
 * </ul>
 *
 * <p>没有 {@code MeterRegistry} 时全部退化为空操作——可观测缺失不该让缓存不可用。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class SemanticCacheMetrics {

    private static final String LOOKUP = "customerwork.semanticcache.lookup";
    private static final String CANDIDATES = "customerwork.semanticcache.candidates";
    private static final String TRUNCATED = "customerwork.semanticcache.candidates.truncated";
    private static final String BEST_SCORE_MISS = "customerwork.semanticcache.miss.bestscore";

    /** 命中。 */
    public static final String RESULT_HIT = "hit";
    /** 查了但没命中（相似度不够，或候选为空）。 */
    public static final String RESULT_MISS = "miss";
    /** 压根没查（缓存关闭、意图不可缓存、含个人标识等）。 */
    public static final String RESULT_SKIP = "skip";

    private final MeterRegistry registry;

    public SemanticCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记一次查询结果。 */
    public void recordLookup(String result) {
        if (registry == null) {
            return;
        }
        Counter.builder(LOOKUP).tag(MetricTags.RESULT, result).register(registry).increment();
    }

    /**
     * 记候选集规模，以及它是否被上限截断。
     *
     * <p>截断意味着「还有更相似的条目没进入比较范围」——这正是 P1-13 要不要做的判据。</p>
     */
    public void recordCandidates(int size, int limit) {
        if (registry == null) {
            return;
        }
        DistributionSummary.builder(CANDIDATES).register(registry).record(size);
        if (limit > 0 && size >= limit) {
            Counter.builder(TRUNCATED).register(registry).increment();
        }
    }

    /**
     * 记未命中时的最高相似度。
     *
     * <p>它回答的是「差多少才命中」：大量落在阈值下沿说明阈值定得太严，
     * 调一个配置就能解决，比改存储格式便宜得多。</p>
     */
    public void recordMissBestScore(double bestScore) {
        if (registry == null || bestScore <= 0) {
            return;
        }
        DistributionSummary.builder(BEST_SCORE_MISS).register(registry).record(bestScore);
    }
}
