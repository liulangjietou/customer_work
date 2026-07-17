package com.richard.fyoung.customerwork.sensitiveword;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内敏感词存储单测：演示种子装载、启用过滤、upsert。
 * @author owlzhangfq@gmail.com
 */
class InMemorySensitiveWordStoreTest {

    @Test
    void constructor_shouldLoadDemoSeeds() {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        assertEquals(SensitiveWordSeeds.demoWords().size(), store.findAll().size());
        assertTrue(store.findEnabled().isPresent(), "进程内读取恒成功");
        assertEquals(store.findAll().size(), store.findEnabled().get().size(), "种子默认全部启用");
    }

    @Test
    void save_shouldAssignIdAndAppend() {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        int before = store.findAll().size();
        store.save(SensitiveWord.of("追加词", SensitiveWordCategory.CUSTOM, SensitiveWordAction.MASK));
        assertEquals(before + 1, store.findAll().size());
        assertTrue(store.findAll().stream().anyMatch(w -> "追加词".equals(w.getWord())));
    }

    @Test
    void disabledWord_shouldBeExcludedFromEnabled() {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        int enabledBefore = store.findEnabled().orElseThrow().size();
        store.save(new SensitiveWord(null, "停用词", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK, false));
        assertEquals(enabledBefore, store.findEnabled().orElseThrow().size(), "停用词不进 findEnabled");
        assertTrue(store.findAll().stream().anyMatch(w -> "停用词".equals(w.getWord())));
    }

    @Test
    void save_shouldIgnoreBlankWord() {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        int before = store.findAll().size();
        store.save(SensitiveWord.of("", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK));
        store.save(null);
        assertEquals(before, store.findAll().size());
        assertFalse(store.findAll().stream().anyMatch(w -> "".equals(w.getWord())));
    }
}
