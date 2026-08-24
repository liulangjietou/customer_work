package com.richard.fyoung.customeradmin.a2a;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2A 服务端装配的租户上下文测试。
 *
 * <p>这个 Bean 在<b>启动期</b>查 {@code ai_agent}——那时没有请求、也就没有租户上下文，
 * 直连查询会被租户拦截器 fail-closed 打断，整个应用起不来（实际发生过）。
 * {@code contextLoads} 照不出来：{@code admin.a2a.enabled} 默认关，那条装配路径根本不执行。</p>
 * @author owlzhangfq@gmail.com
 */
class AdminA2aServerConfigTenantTest {

    /**
     * 断言查询发生在跨租户作用域内。
     *
     * <p>不断言"没抛异常"——单测里本就没挂拦截器，怎么写都不会抛，那种断言恒真、毫无保护力。
     * 这里直接查 MyBatis-Plus 的忽略策略，它正是拦截器运行时读的那一份。</p>
     */
    @Test
    void agentScopeA2aServer_shouldQueryAgentAcrossTenants() {
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        AiAgent agent = new AiAgent();
        agent.setAgentCode("coder");
        agent.setAgentName("编码助手");
        agent.setTenantId("tenant-a");
        when(agentMapper.selectOne(any())).thenAnswer(invocation -> {
            assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId"),
                "启动期查询必须显式跨租户，否则缺上下文会 fail-closed 导致应用起不来");
            return agent;
        });

        AdminA2aProperties properties = new AdminA2aProperties();
        properties.setAgentCode("coder");
        properties.setToken("a2a-test-token");

        assertNotNull(new AdminA2aServerConfig()
            .agentScopeA2aServer(properties, mock(AgentInstanceCache.class),
                mock(com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory.class), agentMapper));
    }

    /** 作用域退出后必须还原，不能把"忽略租户"泄漏给后续调用。 */
    @Test
    void tenantIgnoreShouldNotLeakAfterAssembly() {
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        AiAgent agent = new AiAgent();
        agent.setAgentCode("coder");
        agent.setAgentName("编码助手");
        agent.setTenantId("tenant-a");
        when(agentMapper.selectOne(any())).thenReturn(agent);

        AdminA2aProperties properties = new AdminA2aProperties();
        properties.setAgentCode("coder");
        properties.setToken("a2a-test-token");
        new AdminA2aServerConfig().agentScopeA2aServer(properties, mock(AgentInstanceCache.class),
            mock(com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory.class), agentMapper);

        assertTrue(!InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId"));
    }
}
