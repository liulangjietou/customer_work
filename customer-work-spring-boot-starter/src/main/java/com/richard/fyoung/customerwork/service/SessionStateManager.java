package com.richard.fyoung.customerwork.service;

import io.agentscope.core.session.Session;
import io.agentscope.core.session.SessionManager;
import io.agentscope.core.state.StateModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话状态编排器（对应「中断恢复与状态自动化 · StatePersistence / SessionManager」）。
 *
 * <p>用框架 {@link SessionManager} 把多个 {@link StateModule}（短期记忆、PlanNotebook、Toolkit 状态、
 * Agent 元状态等）按 sessionId 作为一个整体统一 save / load，替代逐个 {@code saveTo/loadFrom} 的手工编排，
 * 便于跨进程恢复与一致性管理。底层 {@link Session} 实现（memory/json/redis/mysql）注入自配置。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SessionStateManager {

    private static final Logger log = LoggerFactory.getLogger(SessionStateManager.class);

    private final Session session;

    public SessionStateManager(Session session) {
        this.session = session;
    }

    private SessionManager managerFor(String sessionId, StateModule... components) {
        SessionManager manager = SessionManager.forSessionId(sessionId).withSession(session);
        for (StateModule component : components) {
            manager.addComponent(component);
        }
        return manager;
    }

    /** 把若干状态组件作为整体保存到该会话。 */
    public void save(String sessionId, StateModule... components) {
        managerFor(sessionId, components).saveSession();
        log.debug("[StateManager] 会话 {} 状态已保存（{} 个组件）", sessionId, components.length);
    }

    /** 若该会话已有持久化状态，则恢复到给定组件中。 */
    public boolean loadIfExists(String sessionId, StateModule... components) {
        SessionManager manager = managerFor(sessionId, components);
        if (!manager.sessionExists()) {
            return false;
        }
        manager.loadIfExists();
        log.debug("[StateManager] 会话 {} 状态已恢复", sessionId);
        return true;
    }

    /** 会话是否存在持久化状态。 */
    public boolean exists(String sessionId) {
        return SessionManager.forSessionId(sessionId).withSession(session).sessionExists();
    }

    /** 删除该会话的持久化状态。 */
    public boolean delete(String sessionId) {
        return SessionManager.forSessionId(sessionId).withSession(session).deleteIfExists();
    }
}
