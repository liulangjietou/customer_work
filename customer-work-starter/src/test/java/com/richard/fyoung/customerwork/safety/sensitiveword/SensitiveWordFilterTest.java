package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词过滤服务单测：三档动作决策、绕过变体命中、打码、放行、热重建、fail-closed 三分支。
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordFilterTest {

    private SensitiveWordFilter filter() {
        return new SensitiveWordFilter(new InMemorySensitiveWordStore(), '*', SensitiveWordAction.BLOCK);
    }

    /** 可切换读成败的存根 store：{@code readFail=true} 时 findEnabled 返回 Optional.empty()（模拟 DB 不可达）。 */
    private static final class ToggleStore implements SensitiveWordStore {
        volatile boolean readFail;
        private final List<SensitiveWord> words;

        ToggleStore(boolean readFail, List<SensitiveWord> words) {
            this.readFail = readFail;
            this.words = words;
        }

        @Override
        public List<SensitiveWord> findAll() {
            return words;
        }

        @Override
        public Optional<List<SensitiveWord>> findEnabled() {
            return readFail ? Optional.empty() : Optional.of(words);
        }

        @Override
        public void save(SensitiveWord word) {
            // no-op
        }
    }

    @Test
    void blockWord_shouldDecideBlock() {
        SensitiveWordFilterResult r = filter().check("我想问测试敏感词A的问题");
        assertTrue(r.blocked());
        assertEquals(SensitiveWordAction.BLOCK, r.decision());
    }

    @Test
    void bypassVariants_shouldStillHit() {
        SensitiveWordFilter f = filter();
        assertTrue(f.check("测*试*敏*感*词*A").blocked());
        assertTrue(f.check("测 试 敏 感 词 Ａ").blocked());
    }

    @Test
    void maskWord_shouldMaskSpanAndDecideMask() {
        SensitiveWordFilterResult r = filter().check("请帮我对比竞品XX的价格");
        assertEquals(SensitiveWordAction.MASK, r.decision());
        assertFalse(r.maskedText().contains("竞品"), "MASK 片段应被打码");
        assertTrue(r.maskedText().contains("*"));
        // 打码不改变长度
        assertEquals(r.originalText().length(), r.maskedText().length());
    }

    @Test
    void reviewWord_shouldPassThroughWithoutMasking() {
        SensitiveWordFilterResult r = filter().check("这里有复核占位内容");
        assertEquals(SensitiveWordAction.REVIEW, r.decision());
        assertEquals(r.originalText(), r.maskedText());
    }

    @Test
    void blockDominatesMask_whenBothPresent() {
        SensitiveWordFilterResult r = filter().check("竞品XX加上测试敏感词A");
        assertEquals(SensitiveWordAction.BLOCK, r.decision(), "同句命中多档应取最高优先级 BLOCK");
    }

    @Test
    void cleanText_shouldPass() {
        SensitiveWordFilterResult r = filter().check("你好，帮我查一下订单物流");
        assertFalse(r.hasHit());
        assertNull(r.decision());
    }

    @Test
    void reload_shouldPickUpNewWord() {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        SensitiveWordFilter f = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        assertFalse(f.check("这里包含新增拦截词").blocked());

        store.save(SensitiveWord.of("新增拦截词", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK));
        f.reload();
        assertTrue(f.check("这里包含新增拦截词").blocked(), "热重建后应命中新词");
    }

    // ---------------- fail-closed 三分支 ----------------

    @Test
    void firstLoadReadFailure_shouldFailClosedAndBlockEverything() {
        // 分支③：首次加载即读失败 -> fail-closed 哨兵，任何非空文本一律拦截
        ToggleStore store = new ToggleStore(true, List.of());
        SensitiveWordFilter f = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);

        assertTrue(f.isFailClosed(), "首次加载失败应进入 fail-closed 哨兵态");
        assertTrue(f.check("完全干净的普通问题").blocked(), "fail-closed 下即使无敏感词也应拦截");
        assertEquals(SensitiveWordAction.BLOCK, f.check("你好").decision());
    }

    @Test
    void reloadReadFailure_shouldKeepLastGoodTable_notWipeToEmpty() {
        // 分支②：已加载好词表后一次读失败 -> 保留旧词表，绝不被冲空
        ToggleStore store = new ToggleStore(false,
            List.of(SensitiveWord.of("测试敏感词A", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK)));
        SensitiveWordFilter f = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        assertTrue(f.check("我想问测试敏感词A").blocked());
        assertFalse(f.isFailClosed());
        int patternsBefore = f.patternCount();

        store.readFail = true;   // 模拟 DB 抖动
        f.reload();

        assertFalse(f.isFailClosed(), "已有好词表时读失败不应进 fail-closed");
        assertEquals(patternsBefore, f.patternCount(), "旧词表不应被清空");
        assertTrue(f.check("我想问测试敏感词A").blocked(), "读失败后旧词表仍生效");
    }

    @Test
    void recoverAfterFailClosed_shouldClearSentinelOnNextGoodReload() {
        // 分支③ -> ①：首次失败进哨兵，DB 恢复后 reload 成功应解除哨兵
        ToggleStore store = new ToggleStore(true,
            List.of(SensitiveWord.of("测试敏感词A", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK)));
        SensitiveWordFilter f = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        assertTrue(f.isFailClosed());

        store.readFail = false;  // DB 恢复
        f.reload();

        assertFalse(f.isFailClosed(), "恢复后应解除 fail-closed 哨兵");
        assertFalse(f.check("完全干净的普通问题").blocked(), "解除哨兵后干净文本应放行");
        assertTrue(f.check("我想问测试敏感词A").blocked(), "解除哨兵后正常词表生效");
    }
}
