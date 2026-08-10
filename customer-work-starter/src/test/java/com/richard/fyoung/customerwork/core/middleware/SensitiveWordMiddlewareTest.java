package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.InMemorySensitiveWordStore;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilterResult;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitDirection;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitRecord;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitSink;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 敏感词中间件单测：入站拦截 / 入站打码 / 出站拦截 / fail-closed / disabled 放行 / direction。
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordMiddlewareTest {

    private static final String INBOUND_SAFE =
        CustomerWorkProperties.SensitiveWord.DEFAULT_INBOUND_SAFE_REPLY;
    private static final String OUTBOUND_SAFE =
        CustomerWorkProperties.SensitiveWord.DEFAULT_OUTBOUND_SAFE_REPLY;

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).name("user").textContent(text).build();
    }

    private Msg assistantMsg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).name("assistant").textContent(text).build();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(registry);
        return p;
    }

    private SensitiveWordMiddleware middleware(CustomerWorkProperties props, SensitiveWordFilter filter) {
        return middleware(props, filter, null);
    }

    /** 带命中日志出口的中间件（hitSink 传 null 表示命中日志关闭，等价于容器里没有该 Bean）。 */
    private SensitiveWordMiddleware middleware(CustomerWorkProperties props, SensitiveWordFilter filter,
                                               SensitiveWordHitSink hitSink) {
        AuditSink sink = new LoggingAuditSink();
        return new SensitiveWordMiddleware(props, filter, sink,
            provider(new SimpleMeterRegistry()), hitSinkProvider(hitSink));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<SensitiveWordHitSink> hitSinkProvider(SensitiveWordHitSink hitSink) {
        ObjectProvider<SensitiveWordHitSink> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(hitSink);
        return p;
    }

    private SensitiveWordFilter realFilter() {
        return new SensitiveWordFilter(new InMemorySensitiveWordStore(), '*', SensitiveWordAction.BLOCK);
    }

    private CustomerWorkProperties enabledProps() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSensitiveWord().setEnabled(true);
        return props;
    }

    @Test
    void disabledByDefault_shouldPassThrough() {
        assertFalse(new CustomerWorkProperties().getSensitiveWord().isEnabled());
    }

    @Test
    void inboundBlock_shouldNotInvokeNextAndReturnSafeReply() {
        SensitiveWordMiddleware mw = middleware(enabledProps(), realFilter());
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        AgentInput input = new AgentInput(List.of(userMsg("我想问测试敏感词A的事")));
        List<AgentEvent> events = mw.onAgent(null, null, input, in -> {
            nextCalled.set(true);
            return Flux.just(new AgentResultEvent(assistantMsg("正常回复")));
        }).collectList().block();

        assertFalse(nextCalled.get(), "命中 BLOCK 不应调用 next");
        assertNotNull(events);
        assertEquals(INBOUND_SAFE, ((AgentResultEvent) events.get(0)).getResult().getTextContent());
    }

    @Test
    void inboundMask_shouldPassMaskedInputToNext() {
        SensitiveWordMiddleware mw = middleware(enabledProps(), realFilter());
        AtomicReference<AgentInput> seen = new AtomicReference<>();

        AgentInput input = new AgentInput(List.of(userMsg("帮我对比竞品XX的价格")));
        mw.onAgent(null, null, input, in -> {
            seen.set(in);
            return Flux.just(new AgentResultEvent(assistantMsg("ok")));
        }).blockLast();

        assertNotNull(seen.get(), "MASK 应放行到 next");
        String passed = seen.get().msgs().get(0).getTextContent();
        assertFalse(passed.contains("竞品"), "竞品XX 应已被打码");
        assertTrue(passed.contains("*"));
    }

    @Test
    void outboundBlock_shouldReplaceAiReplyWithSafeFallback() {
        SensitiveWordMiddleware mw = middleware(enabledProps(), realFilter());

        AgentInput input = new AgentInput(List.of(userMsg("你好")));
        List<AgentEvent> events = mw.onAgent(null, null, input,
            in -> Flux.just(new AgentResultEvent(assistantMsg("这里混入了测试敏感词A的内容")))
        ).collectList().block();

        assertNotNull(events);
        assertEquals(OUTBOUND_SAFE, ((AgentResultEvent) events.get(0)).getResult().getTextContent());
    }

    @Test
    void inboundFailClosed_shouldBlockWhenFilterThrows() {
        SensitiveWordFilter throwing = new SensitiveWordFilter(
            new InMemorySensitiveWordStore(), '*', SensitiveWordAction.BLOCK) {
            @Override
            public SensitiveWordFilterResult check(String rawText) {
                throw new RuntimeException("boom");
            }
        };
        SensitiveWordMiddleware mw = middleware(enabledProps(), throwing);
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        AgentInput input = new AgentInput(List.of(userMsg("任意输入")));
        List<AgentEvent> events = mw.onAgent(null, null, input, in -> {
            nextCalled.set(true);
            return Flux.just(new AgentResultEvent(assistantMsg("正常回复")));
        }).collectList().block();

        assertFalse(nextCalled.get(), "fail-closed：过滤器异常时入站按拦截处理，不调用 next");
        assertNotNull(events);
        assertEquals(INBOUND_SAFE, ((AgentResultEvent) events.get(0)).getResult().getTextContent());
        assertEquals(1, mw.inboundBlockedCount());
    }

    @Test
    void disabled_shouldPassThroughEvenWithSensitiveText() {
        CustomerWorkProperties props = new CustomerWorkProperties(); // enabled=false
        SensitiveWordMiddleware mw = middleware(props, realFilter());
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        AgentInput input = new AgentInput(List.of(userMsg("测试敏感词A")));
        mw.onAgent(null, null, input, in -> {
            nextCalled.set(true);
            return Flux.just(new AgentResultEvent(assistantMsg("ok")));
        }).blockLast();

        assertTrue(nextCalled.get(), "禁用时即使命中也应直通");
    }

    @Test
    void directionOutboundOnly_shouldNotFilterInbound() {
        CustomerWorkProperties props = enabledProps();
        props.getSensitiveWord().setDirection(CustomerWorkProperties.Direction.OUTBOUND);
        SensitiveWordMiddleware mw = middleware(props, realFilter());
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        AgentInput input = new AgentInput(List.of(userMsg("测试敏感词A")));
        mw.onAgent(null, null, input, in -> {
            nextCalled.set(true);
            return Flux.just(new AgentResultEvent(assistantMsg("clean")));
        }).blockLast();

        assertTrue(nextCalled.get(), "direction=outbound 时入站不拦截");
    }
}
