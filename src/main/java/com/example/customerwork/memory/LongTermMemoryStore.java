package com.example.customerwork.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 长期记忆存储（对应深度解析 3.4 的"多租户隔离 + 长期记忆"）。
 *
 * <p>进程内、按租户分区的事实存储，作为 {@link InMemoryLongTermMemory} 的共享底座，
 * 使同一租户的多个会话能共享长期记忆，而不同租户之间严格隔离（ToB 硬要求）。</p>
 *
 * <p>这是一个最小可用实现：召回采用基于字符重合度的轻量打分（伪语义检索）。生产中可替换为
 * 百炼长期记忆 / Mem0 / ReMe，或接入向量库做真正的语义召回——只要继续实现
 * {@code io.agentscope.core.memory.LongTermMemory} 接口即可，调用方无感知。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class LongTermMemoryStore {

    /** tenantId -> 事实列表。 */
    private final Map<String, List<String>> tenantFacts = new ConcurrentHashMap<>();

    /** 记录一条事实。空白与重复（同租户内完全相同）不入库。 */
    public void add(String tenantId, String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        List<String> facts = tenantFacts.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        String trimmed = fact.trim();
        if (!facts.contains(trimmed)) {
            facts.add(trimmed);
        }
    }

    /**
     * 按与查询的相关度召回该租户的若干条记忆。
     *
     * @return 命中的记忆（按相关度降序），无任何相关项时返回空列表
     */
    public List<String> recall(String tenantId, String query, int topK) {
        List<String> facts = tenantFacts.get(tenantId);
        if (facts == null || facts.isEmpty() || query == null || query.isBlank()) {
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

    /** 清空指定租户的全部记忆。 */
    public void clear(String tenantId) {
        tenantFacts.remove(tenantId);
    }

    /** 指定租户已存储的记忆条数（便于观测 / 测试）。 */
    public int size(String tenantId) {
        List<String> facts = tenantFacts.get(tenantId);
        return facts == null ? 0 : facts.size();
    }

    /** 相关度打分：统计查询中出现在事实里的去重字符数（对中文按字、英文按词均有效的最简策略）。 */
    private int score(String query, String fact) {
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
