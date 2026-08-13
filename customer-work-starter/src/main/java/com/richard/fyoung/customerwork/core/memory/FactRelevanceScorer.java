package com.richard.fyoung.customerwork.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 长期记忆召回打分（伪语义检索）：统计查询中出现在事实里的去重字符数，对中文按字、英文按词均有效的最简策略。
 *
 * <p>{@link InMemoryLongTermMemoryStore} 与 {@link MybatisLongTermMemoryStore} 共用这一份——
 * 两个实现的召回结果必须一致，切换 {@code store-mode} 不该改变智能体读到的记忆，
 * 各写一份打分迟早漂移。</p>
 * @author owlzhangfq@gmail.com
 */
final class FactRelevanceScorer {

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
        return facts.stream()
            .map(fact -> Map.entry(fact, score(query, fact)))
            .filter(e -> e.getValue() > 0)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(Math.max(1, topK))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /** 相关度打分：统计查询中出现在事实里的去重字符数。 */
    private static int score(String query, String fact) {
        int hit = 0;
        List<Character> seen = new ArrayList<>();
        for (char c : query.toCharArray()) {
            if (Character.isWhitespace(c) || seen.contains(c)) {
                continue;
            }
            seen.add(c);
            if (fact.indexOf(c) >= 0) {
                hit++;
            }
        }
        return hit;
    }
}
