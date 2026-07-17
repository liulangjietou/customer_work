package com.richard.fyoung.customerwork.service;

import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 会话状态运维门面（AgentScope 2.0 迁移版）。
 *
 * <p>1.x 用 {@code SessionManager} + {@code StateModule} 手工编排"逐组件 save/load"；2.0 中
 * Agent 已无状态，状态按 {@code (userId, sessionId)} 由框架在 {@code call} 链路自动持久化到
 * {@link AgentStateStore}。因此本类不再承担"保存/恢复"职责，退化为对 StateStore 的轻量运维门面：
 * 探测会话是否存在、删除会话、按租户（userId）列举会话，供健康检查 / 维护任务 / 管理接口复用。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SessionStateManager {

    private static final Logger log = LoggerFactory.getLogger(SessionStateManager.class);

    private final AgentStateStore stateStore;

    public SessionStateManager(AgentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /** 会话是否已有持久化状态。 */
    public boolean exists(String userId, String sessionId) {
        return stateStore.exists(userId, sessionId);
    }

    /** 删除该会话的持久化状态。 */
    public void delete(String userId, String sessionId) {
        stateStore.delete(userId, sessionId);
        log.info("[StateManager] session deleted, userId={} sessionId={}", userId, sessionId);
    }

    /** 列举某租户（userId）下所有会话 ID。 */
    public Set<String> listSessions(String userId) {
        return stateStore.listSessionIds(userId);
    }
}
