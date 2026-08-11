package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 慢请求/异常请求留证单测：超阈值计 SLOW、出错计 ERROR、快速成功不留证。
 * @author owlzhangfq@gmail.com
 */
class LatencyMiddlewareSlowRequestTest {

    private static final String METRIC = "customerwork.agent.slow.requests";

    private Msg assistant(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).name("assistant").textContent(text).build();
    }

    @SuppressWarnings("unchecked")
    private LatencyMiddleware middleware(SimpleMeterRegistry registry, long thresholdMs) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getLatency().setSlowRequestThresholdMs(thresholdMs);
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new LatencyMiddleware(props, provider);
    }

    @Test
    void slowRequestIsCaptured() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatencyMiddleware mw = middleware(registry, 1);   // 阈值 1ms

        // 在 onNext 里同步 sleep 制造 ~20ms 耗时：计时从 onSubscribe 起、到 onComplete 止，
        // sleep 落在二者之间才会被计入（defer 里 sleep 发生在 onSubscribe 之前，会被漏计）。
        mw.onAgent(null, null, new AgentInput(List.of()),
                in -> Flux.<AgentEvent>just(new AgentResultEvent(assistant("ok")))
                    .doOnNext(e -> sleepMs(20)))
            .blockLast();

        double count = registry.get(METRIC).tag("outcome", "SLOW").counter().count();
        assertEquals(1.0, count, "超阈值请求应留证一次");
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void erroredRequestIsCapturedRegardlessOfLatency() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatencyMiddleware mw = middleware(registry, 100_000);   // 阈值极大，靠出错触发

        mw.onAgent(null, null, new AgentInput(List.of()),
                in -> Flux.<AgentEvent>error(new RuntimeException("boom")))
            .onErrorResume(e -> Flux.empty())
            .blockLast();

        double count = registry.get(METRIC).tag("outcome", "ERROR").counter().count();
        assertEquals(1.0, count, "出错请求无论耗时都应留证");
    }

    @Test
    void fastSuccessfulRequestIsNotCaptured() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatencyMiddleware mw = middleware(registry, 100_000);   // 阈值极大

        mw.onAgent(null, null, new AgentInput(List.of()),
                in -> Flux.<AgentEvent>just(new AgentResultEvent(assistant("ok"))))
            .blockLast();

        assertNull(registry.find(METRIC).counter(), "快速成功请求不应留证");
    }

    @Test
    void captureDisabledWhenThresholdNonPositiveAndNoError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatencyMiddleware mw = middleware(registry, 0);   // <=0 关闭留证

        mw.onAgent(null, null, new AgentInput(List.of()),
                in -> Flux.defer(() -> {
                    sleepMs(10);
                    return Flux.<AgentEvent>just(new AgentResultEvent(assistant("ok")));
                }))
            .blockLast();

        assertNull(registry.find(METRIC).counter(), "阈值<=0 且无错误时不留证");
    }
}
