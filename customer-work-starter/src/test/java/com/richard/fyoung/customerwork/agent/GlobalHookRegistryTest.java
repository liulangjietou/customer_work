package com.richard.fyoung.customerwork.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局 Hook 注册中心单测：注册/反注册/清理；系统 Hook 为全局状态，每个用例后强制清理。
 * @author owlzhangfq@gmail.com
 */
class GlobalHookRegistryTest {

    /** 无副作用透传 Hook，仅用于注册测试。 */
    static class NoopHook implements Hook {
        @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
            return Mono.just(event);
        }
    }

    private final GlobalHookRegistry registry = new GlobalHookRegistry();

    @AfterEach
    void cleanup() {
        registry.clear();   // 防止泄漏到其它测试创建的 Agent
    }

    @Test
    void shouldTrackRegisteredHooks() {
        NoopHook hook = new NoopHook();
        registry.register(hook);
        assertTrue(registry.getRegistered().contains(hook));
        assertEquals(1, registry.getRegistered().size());
    }

    @Test
    void shouldUnregisterHook() {
        NoopHook hook = new NoopHook();
        registry.register(hook);
        registry.unregister(hook);
        assertTrue(registry.getRegistered().isEmpty());
    }

    @Test
    void clear_shouldRemoveAll() {
        registry.register(new NoopHook());
        registry.register(new NoopHook());
        assertEquals(2, registry.getRegistered().size());
        registry.clear();
        assertTrue(registry.getRegistered().isEmpty());
    }

    @Test
    void shouldIgnoreNull() {
        registry.register(null);
        registry.unregister(null);
        assertTrue(registry.getRegistered().isEmpty());
    }
}
