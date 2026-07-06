package com.richard.fyoung.customerwork.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证 Reactor Context → MDC 桥接的核心机制：requestId / sessionId 从 Context 同步到 MDC，
 * 且不同 Context 之间不串号、无残留。
 * @author owlzhangfq@gmail.com
 */
class MdcContextLifterTest {

    private static final String HOOK = "test-mdc-lifter";

    private void registerHook() {
        Hooks.onEachOperator(HOOK,
            Operators.lift((scannable, subscriber) -> new MdcContextLifter<>(subscriber)));
    }

    @AfterEach
    void tearDown() {
        Hooks.resetOnEachOperator(HOOK);
        MDC.clear();
    }

    @Test
    void requestIdInContextReachesMdc() {
        registerHook();
        AtomicReference<String> captured = new AtomicReference<>();
        Mono.just("x")
            .doOnNext(v -> captured.set(MDC.get(MdcContextLifter.REQUEST_ID_KEY)))
            .contextWrite(ctx -> ctx.put(MdcContextLifter.REQUEST_ID_KEY, "req-123"))
            .block();
        assertEquals("req-123", captured.get());
    }

    @Test
    void sessionIdInContextReachesMdc() {
        registerHook();
        AtomicReference<String> captured = new AtomicReference<>();
        Mono.just("x")
            .doOnNext(v -> captured.set(MDC.get(MdcContextLifter.SESSION_ID_KEY)))
            .contextWrite(ctx -> ctx.put(MdcContextLifter.SESSION_ID_KEY, "tenantA:conv-1"))
            .block();
        assertEquals("tenantA:conv-1", captured.get());
    }

    @Test
    void bothKeysReachMdcTogether() {
        registerHook();
        AtomicReference<String> req = new AtomicReference<>();
        AtomicReference<String> sess = new AtomicReference<>();
        Mono.just("x")
            .doOnNext(v -> {
                req.set(MDC.get(MdcContextLifter.REQUEST_ID_KEY));
                sess.set(MDC.get(MdcContextLifter.SESSION_ID_KEY));
            })
            .contextWrite(ctx -> ctx
                .put(MdcContextLifter.REQUEST_ID_KEY, "r1")
                .put(MdcContextLifter.SESSION_ID_KEY, "s1"))
            .block();
        assertEquals("r1", req.get());
        assertEquals("s1", sess.get());
    }

    @Test
    void absentKeyIsClearedFromMdcNoLeak() {
        registerHook();
        // 先污染 MDC，模拟线程复用时残留的上一次请求的值
        MDC.put(MdcContextLifter.REQUEST_ID_KEY, "stale-value");
        AtomicReference<String> captured = new AtomicReference<>();
        Mono.just("x")
            .doOnNext(v -> captured.set(MDC.get(MdcContextLifter.REQUEST_ID_KEY)))
            .block();  // 无 contextWrite，Context 无 requestId
        assertNull(captured.get(), "Context 无该键时应从 MDC 清除，避免串号");
    }

    @Test
    void eachElementSeesItsOwnContextInFlux() {
        registerHook();
        List<String> seen = new ArrayList<>();
        Flux.just("a", "b", "c")
            .doOnNext(v -> seen.add(MDC.get(MdcContextLifter.REQUEST_ID_KEY)))
            .contextWrite(ctx -> ctx.put(MdcContextLifter.REQUEST_ID_KEY, "flux-req"))
            .blockLast();
        assertEquals(List.of("flux-req", "flux-req", "flux-req"), seen);
    }
}
