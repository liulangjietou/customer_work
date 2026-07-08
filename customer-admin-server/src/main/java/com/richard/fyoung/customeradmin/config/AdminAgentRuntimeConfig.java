package com.richard.fyoung.customeradmin.config;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能体运行时公共 Bean：仿 {@code customer-web} 的 {@code CustomerWebAgentConfig} 手法——
 * 本模块已用 {@code spring.autoconfigure.exclude} 关闭 starter 的自动装配，手动暴露
 * {@link AgentStateStore}/{@link PermissionContextState}，供 {@code AdminAgentInstanceFactory}
 * 动态装配任意智能体时复用（同进程共享，状态按 {@code (userId=agentCode, sessionId)} 天然隔离）。
 *
 * <p>状态存储用 {@link InMemoryAgentStateStore}（进程内，重启丢失）——admin 工作区聊天/VibeCoding
 * 是运营调试场景，非面向终端用户的客服主链路，不需要 {@code customer-work.session.*} 那套
 * 四后端可切换的持久化能力，过度设计不划算。权限上下文用 {@link PermissionMode#DEFAULT} 且不注册
 * 任何规则（trivial，不拦截任何工具调用）——调用方已经过 Sa-Token 鉴权 + {@code agent:*} 权限点校验，
 * 不需要在 Agent 内部再叠一层工具级授权。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AdminAgentRuntimeConfig {

    @Bean
    public AgentStateStore agentStateStore() {
        return new InMemoryAgentStateStore();
    }

    @Bean
    public PermissionContextState permissionContextState() {
        return PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
    }
}
