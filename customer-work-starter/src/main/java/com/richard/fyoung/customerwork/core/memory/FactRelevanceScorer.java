package com.richard.fyoung.customerwork.core.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 长期记忆召回打分。
 *
 * <h3>为什么不再按单字统计</h3>
 * <p>原实现统计"查询中的去重<b>字符</b>有多少出现在事实里"。中文下这个策略近乎失效：
 * 「的」「是」「我」「个」这类虚词几乎出现在每一条事实里，
 * <b>查询里虚词越多，越多无关事实拿到分数</b>。实测「我的那个是不是耳机的」召回的前三名是
 * 手机尾号、收货地址、支付方式——唯独没有耳机那条
 * （{@code MemoryRecallQualityTest#functionWordsMustNotDominate} 钉住这个场景）。</p>
 *
 * <h3>三处改动</h3>
 * <ol>
 *   <li><b>按 bigram 而非单字</b>：中文里相邻两字近似一个词，「耳机」「地址」「顺丰」都是 bigram，
 *       而单个「的」不构成任何信息。这是成本最低、收益最直接的一步。</li>
 *   <li><b>滤掉纯虚词 bigram</b>：两个字都是停用词的组合（「的那」「是不」）不参与打分。</li>
 *   <li><b>按查询长度归一</b>：分数除以查询的有效 bigram 数，让长短查询的分数可比——
 *       否则长查询天然拿高分，topK 的截断点就会随查询长度漂移。</li>
 * </ol>
 *
 * <p><b>仍不是语义检索</b>：同义改写（「忌口」vs「过敏」）依然召不回。真正的向量召回需要给
 * {@code cw_long_term_memory} 加向量列、每条事实入库时调一次 embedding，是独立的一件事；
 * {@code EmbeddingClient} 在本包内至今零引用，那条路要连迁移一起评估。</p>
 *
 * <p>{@link InMemoryLongTermMemoryStore} 与 {@link MybatisLongTermMemoryStore} 共用这一份——
 * 两个实现的召回结果必须一致，切换 {@code store-mode} 不该改变智能体读到的记忆，
 * 各写一份打分迟早漂移。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class FactRelevanceScorer {

    /**
     * 中文高频虚词。
     *
     * <p>刻意只收最高频的那些：停用词表越长，越容易把真正的关键词误杀
     * （「号」在「手机尾号」里是关键信息）。这里的目标不是完整分词，
     * 只是让虚词不再主导排序。</p>
     */
    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "是", "在", "我", "你", "他", "她", "它", "们",
        "有", "和", "就", "不", "人", "都", "一", "上", "也", "很",
        "到", "说", "要", "去", "会", "着", "没", "看", "好", "自",
        "这", "那", "个", "吗", "呢", "吧", "啊", "把", "被", "给",
        "什", "么", "怎", "样", "还", "能", "可", "以", "过", "呀");

    private FactRelevanceScorer() {
    }

    /**
     * 从候选事实中按相关度取前 topK 条。
     *
     * @param facts 候选事实（调用方保证非 null）
     * @param query 查询串，空白直接返回空列表
     * @param topK  最多返回条数（小于 1 时按 1 处理）
     * @return 命中的事实（相关度降序），无任何相关项时返回空列表
     */
    static List<String> topMatches(List<String> facts, String query, int topK) {
        if (facts.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> queryGrams = meaningfulGrams(query);
        if (queryGrams.isEmpty()) {
            // 整句都是虚词（"是吗""这样啊"）：没有可匹配的信息，返回空而不是硬凑几条。
            // 硬凑的后果是把无关记忆塞进提示词，比不召回更糟
            return List.of();
        }
        return facts.stream()
            .map(fact -> Map.entry(fact, score(queryGrams, fact)))
            .filter(e -> e.getValue() > 0)
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(Math.max(1, topK))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * 提取查询里"带信息"的 bigram。
     *
     * <p>查询短到不足两个非空白字符时退回单字，且同样滤掉虚词——
     * 用户真的只打了一个「退」字时，那个字就是全部线索。</p>
     */
    private static Set<String> meaningfulGrams(String query) {
        String compact = query.replaceAll("\\s+", "");
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < compact.length(); i++) {
            String left = String.valueOf(compact.charAt(i));
            String right = String.valueOf(compact.charAt(i + 1));
            // 两个字都是虚词才丢弃：「耳机」保留，「的那」丢弃，
            // 而「机型」这种一半虚词一半实词的仍然保留——宁可多留，不可误杀关键词
            if (STOP_WORDS.contains(left) && STOP_WORDS.contains(right)) {
                continue;
            }
            grams.add(left + right);
        }
        if (grams.isEmpty() && compact.length() == 1 && !STOP_WORDS.contains(compact)) {
            grams.add(compact);
        }
        return grams;
    }

    /**
     * 相关度：事实命中了查询的多少个 bigram，按查询长度归一。
     *
     * <p>归一是为了让不同长度的查询分数可比——不归一的话长查询天然拿高分，
     * 而 topK 的排序在同一次查询内虽不受影响，跨查询的阈值判断就没法做了。</p>
     */
    private static double score(Set<String> queryGrams, String fact) {
        String compactFact = fact.replaceAll("\\s+", "");
        int hit = 0;
        for (String gram : queryGrams) {
            if (compactFact.contains(gram)) {
                hit++;
            }
        }
        return hit == 0 ? 0d : (double) hit / queryGrams.size();
    }

    /** 供测试观察分词结果，便于定位"为什么这条没召回"。 */
    static List<String> debugGrams(String query) {
        return new ArrayList<>(meaningfulGrams(query));
    }
}
