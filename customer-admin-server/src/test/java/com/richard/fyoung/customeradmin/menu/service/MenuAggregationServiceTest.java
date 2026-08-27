package com.richard.fyoung.customeradmin.menu.service;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentService;
import com.richard.fyoung.customeradmin.menu.dto.MenuNode;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link MenuAggregationService} 单测：静态菜单权限剪枝 + 启用中智能体动态拼进 workspace 节点。
 * @author owlzhangfq@gmail.com
 */
class MenuAggregationServiceTest {

    private SysPermission permission(long id, long parentId, String permName, String permCode, int type, int sort) {
        SysPermission p = new SysPermission();
        p.setId(id);
        p.setParentId(parentId);
        p.setPermName(permName);
        p.setPermCode(permCode);
        p.setType(type);
        p.setSort(sort);
        return p;
    }

    private List<SysPermission> defaultMenus() {
        return List.of(
            permission(1, 0, "系统管理", "system", 1, 1),
            permission(3, 0, "智能体工作区", "workspace", 1, 3));
    }

    private AiAgent enabledAgent(long id, String code) {
        AiAgent agent = new AiAgent();
        agent.setId(id);
        agent.setAgentName("客服助手");
        agent.setAgentCode(code);
        agent.setCapabilities("chat,vibecoding");
        agent.setStatus(1);
        return agent;
    }

    @Test
    void buildMenuTree_shouldAttachEnabledAgents_underWorkspaceNode() {
        SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
        when(permissionMapper.selectList(null)).thenReturn(defaultMenus());
        AgentService agentService = mock(AgentService.class);
        when(agentService.listEnabled()).thenReturn(List.of(enabledAgent(100L, "customer-helper")));
        MenuAggregationService service = new MenuAggregationService(permissionMapper, agentService, new PublicDeploymentProperties());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getPermissionList).thenReturn(List.of("system", "workspace"));

            List<MenuNode> tree = service.buildMenuTree();

            MenuNode workspace = tree.stream().filter(n -> "workspace".equals(n.getPermCode())).findFirst().orElseThrow();
            assertEquals(1, workspace.getChildren().size());
            MenuNode agentNode = workspace.getChildren().get(0);
            assertTrue(agentNode.isDynamic());
            assertEquals("customer-helper", agentNode.getAgentCode());
            assertEquals(List.of("chat", "vibecoding"), agentNode.getCapabilities());
        }
    }

    @Test
    void buildMenuTree_shouldNotShowWorkspace_whenNotGranted() {
        SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
        when(permissionMapper.selectList(null)).thenReturn(defaultMenus());
        AgentService agentService = mock(AgentService.class);
        when(agentService.listEnabled()).thenReturn(List.of(enabledAgent(100L, "customer-helper")));
        MenuAggregationService service = new MenuAggregationService(permissionMapper, agentService, new PublicDeploymentProperties());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getPermissionList).thenReturn(List.of("system"));

            List<MenuNode> tree = service.buildMenuTree();

            assertTrue(tree.stream().noneMatch(n -> "workspace".equals(n.getPermCode())));
        }
    }

    /**
     * 对外开放实例上，内部运维工具的菜单不下发——即使当前用户拥有对应权限点。
     *
     * <p>与权限过滤是两道独立的闸门：权限过滤回答"这个人能不能用"，这里回答"这个实例有没有这个能力"。
     * 对应接口已由 InternalToolGuardInterceptor 拒绝，菜单还留着只会给出一个点进去必然报错的入口。</p>
     */
    @Test
    void buildMenuTree_shouldHideInternalToolMenusOnPublicDeployment() {
        SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
        when(permissionMapper.selectList(null)).thenReturn(List.of(
            permission(1, 0, "系统管理", "system", 1, 1),
            permission(110, 0, "SQL配置管理", "sql-config", 1, 2),
            permission(160, 0, "我的工作台", "workbench", 1, 3),
            permission(161, 160, "开发者工具箱", "devtools", 1, 1),
            permission(162, 160, "SQL 客户端", "sql-console:query", 1, 2)));
        AgentService agentService = mock(AgentService.class);
        when(agentService.listEnabled()).thenReturn(List.of());
        PublicDeploymentProperties publicDeployment = new PublicDeploymentProperties();
        publicDeployment.setEnabled(true);
        MenuAggregationService service =
            new MenuAggregationService(permissionMapper, agentService, publicDeployment);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            // 故意给全量权限：菜单仍不该出现，证明这道闸门与权限无关
            stpUtil.when(StpUtil::getPermissionList).thenReturn(
                List.of("system", "sql-config", "workbench", "devtools", "sql-console:query"));

            List<MenuNode> tree = service.buildMenuTree();

            assertEquals(List.of("system"), tree.stream().map(MenuNode::getPermCode).toList());
        }
    }

    /** 内网实例保持原样：这些菜单正是内部工程师的主力功能。 */
    @Test
    void buildMenuTree_shouldKeepInternalToolMenusOnInternalDeployment() {
        SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
        when(permissionMapper.selectList(null)).thenReturn(List.of(
            permission(1, 0, "系统管理", "system", 1, 1),
            permission(160, 0, "我的工作台", "workbench", 1, 2),
            permission(161, 160, "开发者工具箱", "devtools", 1, 1)));
        AgentService agentService = mock(AgentService.class);
        when(agentService.listEnabled()).thenReturn(List.of());
        MenuAggregationService service =
            new MenuAggregationService(permissionMapper, agentService, new PublicDeploymentProperties());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getPermissionList)
                .thenReturn(List.of("system", "workbench", "devtools"));

            List<MenuNode> tree = service.buildMenuTree();

            assertEquals(List.of("system", "workbench"),
                tree.stream().map(MenuNode::getPermCode).toList());
            assertEquals(List.of("devtools"),
                tree.get(1).getChildren().stream().map(MenuNode::getPermCode).toList());
        }
    }
}
