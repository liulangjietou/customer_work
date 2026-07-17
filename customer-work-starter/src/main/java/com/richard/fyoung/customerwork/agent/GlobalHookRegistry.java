package com.richard.fyoung.customerwork.agent;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局 Hook 注册中心（对应「Hook 生命周期 · 运行时热插拔」）。
 *
 * <p>封装 {@link AgentBase#addSystemHook(Hook)} / {@link AgentBase#removeSystemHook(Hook)}，
 * 支持运行时把横切 Hook（如全局鉴权、全局审计、灰度埋点）动态注册为<b>系统级 Hook</b>，
 * 对<b>之后创建</b>的所有 Agent 生效，无需重启或改装配。本注册中心跟踪自己注册的 Hook，
 * 便于干净地反注册与应用关闭时清理。</p>
 *
 * <p>注意：系统 Hook 是进程级全局状态，作用于所有 {@code AgentBase} 实例；请只用于真正的横切关注点。
 * 已在 build 期织入的会话级 Hook（延迟/脱敏/审计等）不经过这里。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class GlobalHookRegistry {

    private static final Logger log = LoggerFactory.getLogger(GlobalHookRegistry.class);

    private final List<Hook> registered = new CopyOnWriteArrayList<>();

    /** 注册一个全局系统 Hook（对之后创建的所有 Agent 生效）。 */
    public void register(Hook hook) {
        if (hook == null) {
            return;
        }
        AgentBase.addSystemHook(hook);
        registered.add(hook);
        log.info("[HOOK] 已注册全局系统 Hook: {} (priority={})",
            hook.getClass().getSimpleName(), hook.priority());
    }

    /** 反注册一个之前注册的全局系统 Hook。 */
    public void unregister(Hook hook) {
        if (hook == null) {
            return;
        }
        AgentBase.removeSystemHook(hook);
        registered.remove(hook);
        log.info("[HOOK] 已反注册全局系统 Hook: {}", hook.getClass().getSimpleName());
    }

    /** 当前由本注册中心注册的全局 Hook 快照。 */
    public List<Hook> getRegistered() {
        return List.copyOf(registered);
    }

    /** 应用关闭时清理本注册中心注册过的全局 Hook，避免泄漏到后续上下文。 */
    @PreDestroy
    public void clear() {
        for (Hook hook : registered) {
            AgentBase.removeSystemHook(hook);
        }
        registered.clear();
    }
}
