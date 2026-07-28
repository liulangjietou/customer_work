package com.richard.fyoung.customerwork.sensitiveword;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式滑动缓冲过滤单测——出站过滤真正生效的地方。
 *
 * <p>接入层用的 {@code agent.stream(...)} 走 StreamingHook 旁路绕开中间件，中间件只看得到最后那条
 * AGENT_RESULT，而它会被接入层当作"整段回放"丢弃。所以出站能不能拦住，全看这个 guard。</p>
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordStreamGuardTest {

    private static final String SAFE_REPLY = "抱歉，这个问题我暂时无法回答。";

    private SensitiveWordStreamGuard guard(SensitiveWordAction action, String word) {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        store.save(SensitiveWord.of(word, SensitiveWordCategory.CUSTOM, action));
        return new SensitiveWordStreamGuard(
            new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK), SAFE_REPLY);
    }

    /** 按片喂入并拼接放行结果 + flush 尾巴，等价于用户最终看到的正文。 */
    private String feed(SensitiveWordStreamGuard guard, String... deltas) {
        StringBuilder sb = new StringBuilder();
        for (String d : deltas) {
            sb.append(guard.accept(d));
        }
        sb.append(guard.flush());
        return sb.toString();
    }

    @Test
    void mask_shouldCatchWordSplitAcrossDeltas() {
        // 逐片匹配必漏，只有滑动缓冲能命中——这条是整个流式过滤的立身之本
        assertEquals("冠军是***队", feed(guard(SensitiveWordAction.MASK, "阿根廷"), "冠军是阿", "根", "廷队"));
    }

    @Test
    void mask_shouldCatchWordSplitCharByChar() {
        assertEquals("由***夺冠", feed(guard(SensitiveWordAction.MASK, "阿根廷"), "由", "阿", "根", "廷", "夺", "冠"));
    }

    @Test
    void mask_shouldNotLoseAnyCharacterWhenNoHit() {
        // 尾部留住的字符必须靠 flush 吐回来，否则正文末尾会被吞
        assertEquals("法国队夺冠了", feed(guard(SensitiveWordAction.MASK, "阿根廷"), "法国", "队夺冠了"));
    }

    @Test
    void mask_shouldHandleWordAtVeryEnd() {
        assertEquals("冠军是***", feed(guard(SensitiveWordAction.MASK, "阿根廷"), "冠军是阿根廷"));
    }

    @Test
    void mask_shouldHandleMultipleOccurrences() {
        assertEquals("***和***", feed(guard(SensitiveWordAction.MASK, "阿根廷"), "阿根", "廷和阿根廷"));
    }

    @Test
    void block_shouldStopStreamAndReplaceWithSafeReply() {
        SensitiveWordStreamGuard g = guard(SensitiveWordAction.BLOCK, "违禁词");
        String text = feed(g, "前半段", "出现违禁词了", "后面还有内容");

        assertTrue(text.contains(SAFE_REPLY), "命中 BLOCK 要补安全话术");
        assertFalse(text.contains("违禁词"), "命中词绝不能出现在输出里");
        assertFalse(text.contains("后面还有内容"), "拦下后必须停止后续输出");
        assertTrue(g.isBlocked());
    }

    @Test
    void block_shouldSwallowEverythingAfterBlocked() {
        SensitiveWordStreamGuard g = guard(SensitiveWordAction.BLOCK, "违禁词");
        g.accept("违禁词");

        assertEquals("", g.accept("后续内容"), "拦下后任何增量都不放行");
        assertEquals("", g.flush(), "拦下后 flush 也不吐东西");
    }

    @Test
    void flush_shouldBeIdempotent() {
        SensitiveWordStreamGuard g = guard(SensitiveWordAction.MASK, "阿根廷");
        g.accept("普通文本");

        assertEquals("普通文本", g.flush());
        assertEquals("", g.flush(), "重复 flush 不应重复吐出内容");
    }

    @Test
    void emptyDelta_shouldNotBreakBuffer() {
        assertEquals("正常内容", feed(guard(SensitiveWordAction.MASK, "阿根廷"), "正常", "", null, "内容"));
    }
}
