package com.richard.fyoung.customerwork.sensitiveword;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aho-Corasick 多模式串匹配自动机（自研，零第三方依赖）。
 *
 * <p>由词表一次构建为 <b>不可变</b> DFA（goto 表 + 失败指针 + 输出链），单趟扫描 O(n + 命中数) 返回全部命中，
 * 与词表规模无关。构建后所有字段只读，天然线程安全——热重建走"构建新实例 + 原子替换引用"，匹配热路径无锁
 * （见 {@link SensitiveWordFilter}）。</p>
 *
 * <p>字符粒度基于 Java {@code char}（UTF-16），中文（BMP）为单 char，演示场景足够；扫描前文本与词面均已过
 * {@link TextNormalizer} 归一化，命中下标为归一化文本内的偏移。</p>
 * @author owlzhangfq@gmail.com
 */
public final class AhoCorasickMatcher {

    /** 空自动机单例（词表为空时复用，match 恒返回空）。 */
    private static final AhoCorasickMatcher EMPTY = new AhoCorasickMatcher(Collections.emptyList());

    /** goto 表：节点 -> (字符 -> 子节点)。 */
    private final List<Map<Character, Integer>> gotoTable = new ArrayList<>();
    /** 失败指针。 */
    private final List<Integer> fail = new ArrayList<>();
    /** 每个节点的输出（以该节点结尾的模式，含经失败链继承的），元素为词表下标。 */
    private final List<List<Integer>> output = new ArrayList<>();
    /** 词表（下标与 output 元素对应）。 */
    private final List<SensitiveWord> patterns;

    private AhoCorasickMatcher(List<SensitiveWord> words) {
        this.patterns = new ArrayList<>(words);
        newNode(); // root = 0
        buildGoto();
        buildFail();
    }

    /**
     * 由词表构建自动机（跳过空词面）。
     *
     * @param words 已归一化词面的敏感词集合
     * @return 不可变自动机；词表为空时返回共享空实例
     */
    public static AhoCorasickMatcher build(List<SensitiveWord> words) {
        if (words == null || words.isEmpty()) {
            return EMPTY;
        }
        List<SensitiveWord> valid = new ArrayList<>(words.size());
        for (SensitiveWord w : words) {
            if (w != null && w.getWord() != null && !w.getWord().isEmpty()) {
                valid.add(w);
            }
        }
        return valid.isEmpty() ? EMPTY : new AhoCorasickMatcher(valid);
    }

    /** 词表规模（供观测 / 单测）。 */
    public int patternCount() {
        return patterns.size();
    }

    /**
     * 单趟扫描，返回全部命中（含重叠命中）。
     *
     * @param normalizedText 已归一化文本
     * @return 命中列表（按结束位置自然有序）；无命中返回空列表
     */
    public List<SensitiveWordHit> match(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty() || patterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<SensitiveWordHit> hits = new ArrayList<>();
        int node = 0;
        for (int i = 0; i < normalizedText.length(); i++) {
            char c = normalizedText.charAt(i);
            node = step(node, c);
            for (int patternIdx : output.get(node)) {
                SensitiveWord word = patterns.get(patternIdx);
                int end = i + 1;
                int start = end - word.getWord().length();
                hits.add(new SensitiveWordHit(word, start, end));
            }
        }
        return hits;
    }

    /** 沿 goto/fail 转移一步（root 无匹配时停在 root）。 */
    private int step(int node, char c) {
        Integer next = gotoTable.get(node).get(c);
        while (next == null && node != 0) {
            node = fail.get(node);
            next = gotoTable.get(node).get(c);
        }
        return next == null ? 0 : next;
    }

    private void buildGoto() {
        for (int p = 0; p < patterns.size(); p++) {
            String word = patterns.get(p).getWord();
            int node = 0;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                Integer child = gotoTable.get(node).get(c);
                if (child == null) {
                    child = newNode();
                    gotoTable.get(node).put(c, child);
                }
                node = child;
            }
            output.get(node).add(p);
        }
    }

    /** BFS 构建失败指针，并把失败节点的输出并入本节点（输出链展开）。 */
    private void buildFail() {
        Deque<Integer> queue = new ArrayDeque<>();
        // root 的直接子节点失败指针指向 root
        for (int child : gotoTable.get(0).values()) {
            fail.set(child, 0);
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            for (Map.Entry<Character, Integer> entry : gotoTable.get(cur).entrySet()) {
                char c = entry.getKey();
                int child = entry.getValue();
                int f = fail.get(cur);
                while (f != 0 && gotoTable.get(f).get(c) == null) {
                    f = fail.get(f);
                }
                Integer target = gotoTable.get(f).get(c);
                fail.set(child, (target == null || target == child) ? 0 : target);
                output.get(child).addAll(output.get(fail.get(child)));
                queue.add(child);
            }
        }
    }

    private int newNode() {
        gotoTable.add(new HashMap<>());
        fail.add(0);
        output.add(new ArrayList<>());
        return gotoTable.size() - 1;
    }
}
