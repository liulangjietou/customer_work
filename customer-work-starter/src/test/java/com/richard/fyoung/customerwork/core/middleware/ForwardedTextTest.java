package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import com.richard.fyoung.customerwork.safety.security.SensitiveDataMasker;
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
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 改写正文的出站中间件对转发正文的两条约定（{@link ForwardedText}）：改写后沿用来源、缓冲按来源分开。
 *
 * <p>场景是子智能体中途失败——它的文本块只有开头没有结束，主智能体随后照常答完。框架给每个文本块的
 * blockId 都是 {@code "text"}，只按 blockId 缓冲时，主智能体的正文会续进子智能体那份缓冲、顶着子智能体的来源放出，
 * 用户端（按来源丢弃转发正文）于是看不到主智能体的答复。真实 Agent 的整链验证见
 * {@code SubagentForwardedEventsTest}。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ForwardedTextTest {

    private static final String BLOCK = "text";
    private static final String CHILD = "u1:conv/AfterSalesExpert";
    private static final String PARENT_ANSWER = "已为您登记退款申请。";
    /** 子智能体中断前的最后一片：以敏感词「傻瓜」的前缀收尾，敏感词过滤会把这个字压在缓冲里等下一片。 */
    private static final String CHILD_PARTIAL = "售后专员电话 13812345678，傻";

    @Test
    @DisplayName("脱敏：子智能体的块没结束，主智能体的块照样整块放出、不带子智能体的来源；子智能体那块收尾时沿用来源")
    void maskingKeepsParentBlockApartFromUnfinishedChildBlock() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(true);
        List<AgentEvent> out = run(new MaskingMiddleware(props, new SensitiveDataMasker(props)));

        assertEquals(PARENT_ANSWER, text(out, null));
        assertEquals("售后专员电话 ***，傻", text(out, CHILD), "子智能体的正文照常脱敏并认得出来源");
    }

    @Test
    @DisplayName("敏感词：子智能体的块没结束，主智能体的正文不续进它的缓冲，也不顶着它的来源放出")
    void sensitiveWordsKeepParentBlockApartFromUnfinishedChildBlock() {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        store.save(SensitiveWord.of("傻瓜", SensitiveWordCategory.CUSTOM, SensitiveWordAction.MASK));
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSensitiveWord().setEnabled(true);
        List<AgentEvent> out = run(new SensitiveWordMiddleware(props,
            new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK), new LoggingAuditSink(),
            provider((MeterRegistry) new SimpleMeterRegistry()), provider((SensitiveWordHitSink) null)));

        assertEquals(PARENT_ANSWER, text(out, null), "子智能体压在缓冲里的「傻」不应接到主智能体的答复开头");
    }

    /** 子智能体一片正文后中断（没有块结束），主智能体随后完整答完一块。 */
    private static List<AgentEvent> run(MiddlewareBase middleware) {
        List<AgentEvent> upstream = List.of(
            new TextBlockDeltaEvent("child-reply", BLOCK, CHILD_PARTIAL).withSource(CHILD),
            new TextBlockDeltaEvent("parent-reply", BLOCK, PARENT_ANSWER),
            new TextBlockEndEvent("parent-reply", BLOCK));
        return middleware.onAgent(null, null, new AgentInput(List.of()), in -> Flux.fromIterable(upstream))
            .collectList().block();
    }

    private static String text(List<AgentEvent> events, String source) {
        return events.stream()
            .filter(e -> e instanceof TextBlockDeltaEvent && Objects.equals(source, e.getSource()))
            .map(e -> ((TextBlockDeltaEvent) e).getDelta())
            .collect(Collectors.joining());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
