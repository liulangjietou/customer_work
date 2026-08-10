package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.InMemorySensitiveWordStore;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWord;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordCategory;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitSink;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式出站过滤单测：这是整个出站链路真正生效的地方——接入层把 {@link TextBlockDeltaEvent} 逐片推给前端后，
 * 最终的 AgentResultEvent 会被丢弃，只改最终结果等于没改。
 *
 * <p>覆盖：跨片段命中打码、尾部保留不漏字、块结束 flush、BLOCK 截流、无命中原样透传。</p>
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordStreamFilterTest {

    private static final String REPLY = "reply-1";
    private static final String BLOCK_ID = "block-1";

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private SensitiveWordMiddleware middleware(SensitiveWordAction action, String word) {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        store.save(SensitiveWord.of(word, SensitiveWordCategory.CUSTOM, action));
        SensitiveWordFilter filter = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSensitiveWord().setEnabled(true);
        return new SensitiveWordMiddleware(props, filter, new LoggingAuditSink(),
            provider((MeterRegistry) new SimpleMeterRegistry()), provider((SensitiveWordHitSink) null));
    }

    /** 把若干片文本按流式事件喂进中间件，返回前端最终会看到的正文。 */
    private String streamThrough(SensitiveWordMiddleware mw, String... deltas) {
        List<AgentEvent> upstream = new ArrayList<>();
        for (String d : deltas) {
            upstream.add(new TextBlockDeltaEvent(REPLY, BLOCK_ID, d));
        }
        upstream.add(new TextBlockEndEvent(REPLY, BLOCK_ID));

        List<AgentEvent> out = mw.onAgent(null, null, new AgentInput(List.of()), input -> Flux.fromIterable(upstream))
            .collectList().block();

        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : out == null ? List.<AgentEvent>of() : out) {
            if (e instanceof TextBlockDeltaEvent d) {
                sb.append(d.getDelta());
            }
        }
        return sb.toString();
    }

    @Test
    void mask_shouldCatchWordSplitAcrossDeltas() {
        // "阿根廷" 被拆进三片推送——逐片匹配必漏，只有滑动缓冲能命中
        String text = streamThrough(middleware(SensitiveWordAction.MASK, "阿根廷"), "冠军是阿", "根", "廷队");

        assertEquals("冠军是***队", text, "跨片段的词必须被打码");
    }

    @Test
    void mask_shouldNotLoseTailCharacters() {
        // 尾部保留的字符必须在块结束时 flush 出来，否则正文末尾会被吞掉
        String text = streamThrough(middleware(SensitiveWordAction.MASK, "阿根廷"), "结尾没有敏感内容");

        assertEquals("结尾没有敏感内容", text, "无命中时一个字都不能少");
    }

    @Test
    void mask_shouldHandleWordAtVeryEnd() {
        String text = streamThrough(middleware(SensitiveWordAction.MASK, "阿根廷"), "冠军是阿根廷");

        assertEquals("冠军是***", text, "落在正文末尾的词靠 flush 兜住");
    }

    @Test
    void mask_shouldPassThroughWhenNoHit() {
        String text = streamThrough(middleware(SensitiveWordAction.MASK, "阿根廷"), "法国", "队夺冠");

        assertEquals("法国队夺冠", text);
    }

    @Test
    void block_shouldStopStreamAndReplaceWithSafeReply() {
        SensitiveWordMiddleware mw = middleware(SensitiveWordAction.BLOCK, "违禁词");
        String text = streamThrough(mw, "前半段正常", "这里有违禁词", "后面还有很多内容");

        String safeReply = CustomerWorkProperties.SensitiveWord.DEFAULT_OUTBOUND_SAFE_REPLY;
        assertTrue(text.contains(safeReply), "命中 BLOCK 应补一条安全话术");
        assertFalse(text.contains("违禁词"), "命中词本身绝不能出现在输出里");
        assertFalse(text.contains("后面还有很多内容"), "拦下后必须停止后续输出，不能继续吐");
    }

    @Test
    void block_shouldNotEmitAnythingAfterBlocked() {
        SensitiveWordMiddleware mw = middleware(SensitiveWordAction.BLOCK, "违禁词");
        List<AgentEvent> upstream = List.of(
            new TextBlockDeltaEvent(REPLY, BLOCK_ID, "违禁词来了"),
            new TextBlockDeltaEvent(REPLY, BLOCK_ID, "继续输出"),
            new TextBlockEndEvent(REPLY, BLOCK_ID));

        List<AgentEvent> out = mw.onAgent(null, null, new AgentInput(List.of()),
            input -> Flux.fromIterable(upstream)).collectList().block();

        long deltaCount = out == null ? 0 : out.stream().filter(e -> e instanceof TextBlockDeltaEvent).count();
        assertEquals(1, deltaCount, "只应发出那条安全话术，后续增量全部丢弃");
    }
}
