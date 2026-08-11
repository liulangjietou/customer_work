package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aho-Corasick 自动机单测：多词命中、重叠命中、位置、空表。
 * @author owlzhangfq@gmail.com
 */
class AhoCorasickMatcherTest {

    private SensitiveWord w(String word) {
        return SensitiveWord.of(word, SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK);
    }

    @Test
    void multiWord_shouldReturnAllHitsWithPositions() {
        AhoCorasickMatcher m = AhoCorasickMatcher.build(List.of(w("abc"), w("cde")));
        List<SensitiveWordHit> hits = m.match("xabcdey");
        assertEquals(2, hits.size());
        // abc 命中 [1,4)
        assertTrue(hits.stream().anyMatch(h -> h.word().getWord().equals("abc") && h.start() == 1 && h.end() == 4));
        // cde 命中 [3,6)
        assertTrue(hits.stream().anyMatch(h -> h.word().getWord().equals("cde") && h.start() == 3 && h.end() == 6));
    }

    @Test
    void overlappingPatterns_shouldAllMatch_classicUshers() {
        // 经典重叠用例：she / he / hers 均在 "ushers" 中命中
        AhoCorasickMatcher m = AhoCorasickMatcher.build(List.of(w("he"), w("she"), w("hers")));
        List<SensitiveWordHit> hits = m.match("ushers");
        assertTrue(hits.stream().anyMatch(h -> h.word().getWord().equals("she")));
        assertTrue(hits.stream().anyMatch(h -> h.word().getWord().equals("he")));
        assertTrue(hits.stream().anyMatch(h -> h.word().getWord().equals("hers")));
    }

    @Test
    void chineseWords_shouldMatch() {
        AhoCorasickMatcher m = AhoCorasickMatcher.build(List.of(w("测试敏感词a"), w("竞品xx")));
        assertEquals(1, m.match("我想问测试敏感词a的事").size());
        assertEquals(2, m.match("测试敏感词a和竞品xx").size());
    }

    @Test
    void emptyOrNoDict_shouldReturnNoHit() {
        assertTrue(AhoCorasickMatcher.build(List.of()).match("anything").isEmpty());
        AhoCorasickMatcher m = AhoCorasickMatcher.build(List.of(w("abc")));
        assertTrue(m.match("").isEmpty());
        assertTrue(m.match("xyz").isEmpty());
    }

    @Test
    void patternCount_shouldSkipBlankWords() {
        AhoCorasickMatcher m = AhoCorasickMatcher.build(List.of(w("abc"), w(""), w("def")));
        assertEquals(2, m.patternCount());
    }
}
