package com.richard.fyoung.customeradmin.workspace.runtime.mode;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级执行模式注册表（进程内、重启丢失）：把"本次会话选了哪档模式"从 stream 订阅侧传递到运行时
 * 的 {@code ExecutionModeMiddleware}——Agent 实例被 {@code AgentInstanceCache} 缓存复用，中间件必须
 * 运行时按 {@code (agentCode, sessionId)} 读模式，不能在构建期固化。
 *
 * <p>键规则与 {@code PlanConfirmationService.channelKey} 同风格（{@code agentCode:sessionId}，
 * sessionId 空回退 {@code default}）。stream 订阅时 {@link #put}，{@code doFinally} 时 {@link #remove}；
 * 取不到（未指定/已清理）由中间件回落到全局 {@code AdminSandboxProperties.permissionMode} 语义。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExecutionModeRegistry {

    private static final String DEFAULT_SESSION = "default";

    /** {@code agentCode:sessionId -> 会话选定的执行模式}。 */
    private final Map<String, ExecutionMode> modes = new ConcurrentHashMap<>();

    /** 登记会话模式（{@code null} 视为未指定，直接清除已有登记，交由全局回落）。 */
    public void put(String agentCode, String sessionId, ExecutionMode mode) {
        String key = key(agentCode, sessionId);
        if (mode == null) {
            modes.remove(key);
            return;
        }
        modes.put(key, mode);
    }

    /** 取会话模式；未登记返回 {@code null}（调用方据此走全局回落）。 */
    public ExecutionMode get(String agentCode, String sessionId) {
        return modes.get(key(agentCode, sessionId));
    }

    /** 摘除会话模式登记（stream 生命周期结束时调用）。 */
    public void remove(String agentCode, String sessionId) {
        modes.remove(key(agentCode, sessionId));
    }

    private String key(String agentCode, String sessionId) {
        return agentCode + ":" + (StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION);
    }
}
