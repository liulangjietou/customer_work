package com.richard.fyoung.customeradmin.menu.service;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentService;
import com.richard.fyoung.customeradmin.common.constant.AgentCapabilities;
import com.richard.fyoung.customeradmin.common.constant.TreeConstants;
import com.richard.fyoung.customeradmin.menu.dto.MenuNode;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customeradmin.tenant.ControlPlanePermissions;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态菜单聚合：合并"静态菜单"（{@code sys_permission} type=1，按当前用户权限点过滤）与
 * "动态菜单"（各启用中智能体入口，运行时拼进 {@code workspace} 节点、不落库——智能体增删启停
 * 立即反映，无额外一致性同步逻辑）。
 * @author owlzhangfq@gmail.com
 */
@Service
public class MenuAggregationService {

    private static final int TYPE_MENU = 1;
    private static final String WORKSPACE_PERM_CODE = "workspace";

    private final SysPermissionMapper permissionMapper;
    private final AgentService agentService;
    private final PublicDeploymentProperties publicDeployment;

    public MenuAggregationService(SysPermissionMapper permissionMapper, AgentService agentService,
                                  PublicDeploymentProperties publicDeployment) {
        this.permissionMapper = permissionMapper;
        this.agentService = agentService;
        this.publicDeployment = publicDeployment;
    }

    /** 当前登录用户可见的菜单树：静态菜单按权限点过滤 + workspace 节点下挂启用中的智能体动态节点。 */
    public List<MenuNode> buildMenuTree() {
        Set<String> granted = new HashSet<>(StpUtil.getPermissionList());
        List<SysPermission> menus = permissionMapper.selectList(null).stream()
            .filter(p -> p.getType() != null && p.getType() == TYPE_MENU)
            .filter(this::visibleInCurrentDeployment)
            .toList();

        Map<Long, MenuNode> nodeById = new LinkedHashMap<>();
        Map<Long, Long> parentById = new LinkedHashMap<>();
        Map<Long, String> permCodeById = new LinkedHashMap<>();
        for (SysPermission p : menus) {
            nodeById.put(p.getId(), toNode(p));
            parentById.put(p.getId(), p.getParentId() == null ? TreeConstants.ROOT_PARENT_ID : p.getParentId());
            permCodeById.put(p.getId(), p.getPermCode());
        }

        // 保留"自身被授权"或"有后代节点被授权"的节点（标准的树剪枝：保留祖先路径不丢失结构）
        Set<Long> keep = new HashSet<>();
        for (SysPermission p : menus) {
            if (granted.contains(p.getPermCode())) {
                Long cur = p.getId();
                while (cur != null && keep.add(cur)) {
                    cur = parentById.get(cur);
                }
            }
        }

        List<MenuNode> roots = new java.util.ArrayList<>();
        for (SysPermission p : menus) {
            if (!keep.contains(p.getId())) {
                continue;
            }
            MenuNode node = nodeById.get(p.getId());
            Long parentId = parentById.get(p.getId());
            if (parentId == null || parentId == TreeConstants.ROOT_PARENT_ID) {
                roots.add(node);
            } else if (keep.contains(parentId)) {
                nodeById.get(parentId).getChildren().add(node);
            } else {
                roots.add(node);   // 父节点不可见（数据异常）时兜底挂根
            }
        }
        mergeDynamicAgentNodes(roots);
        sortTree(roots);
        return roots;
    }

    /**
     * 对外开放实例上剔除内部运维工具菜单。
     *
     * <p>与权限点过滤是两道独立的闸门，不能互相替代：权限过滤回答“这个人能不能用”，
     * 这里回答“这个实例有没有这个能力”。超管在对外实例上同样看不到——
     * 对应接口已由 {@code InternalToolGuardInterceptor} 拒绝，
     * 菜单还留着只会给出一个点进去必然报错的入口。</p>
     */
    private boolean visibleInCurrentDeployment(SysPermission permission) {
        if (!publicDeployment.isEnabled()) {
            return true;
        }
        return !ControlPlanePermissions.isInternalTool(permission.getPermCode());
    }

    /** 把启用中的智能体拼进 workspace 节点（若当前用户没有 workspace 权限，节点本就不在树里，天然不可见）。 */
    private void mergeDynamicAgentNodes(List<MenuNode> roots) {
        MenuNode workspace = findByPermCode(roots, WORKSPACE_PERM_CODE);
        if (workspace == null) {
            return;
        }
        for (AiAgent agent : agentService.listEnabled()) {
            workspace.getChildren().add(toDynamicAgentNode(agent));
        }
    }

    private MenuNode findByPermCode(List<MenuNode> nodes, String permCode) {
        for (MenuNode node : nodes) {
            if (permCode.equals(node.getPermCode())) {
                return node;
            }
            MenuNode found = findByPermCode(node.getChildren(), permCode);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private MenuNode toDynamicAgentNode(AiAgent agent) {
        MenuNode node = new MenuNode();
        node.setId(agent.getId());
        node.setName(agent.getAgentName());
        node.setPath("/workspace/" + agent.getAgentCode());
        node.setIcon(agent.getIcon());
        node.setAgentCode(agent.getAgentCode());
        node.setCapabilities(Arrays.asList(agent.getCapabilities().split(AgentCapabilities.DELIMITER)));
        node.setDynamic(true);
        return node;
    }

    private void sortTree(List<MenuNode> nodes) {
        nodes.sort(Comparator.comparing(MenuNode::getSort, Comparator.nullsLast(Integer::compareTo)));
        for (MenuNode node : nodes) {
            sortTree(node.getChildren());
        }
    }

    private MenuNode toNode(SysPermission p) {
        MenuNode node = new MenuNode();
        node.setId(p.getId());
        node.setName(p.getPermName());
        node.setPath(p.getPath());
        node.setIcon(p.getIcon());
        node.setIconType(p.getIconType());
        node.setPermCode(p.getPermCode());
        node.setSort(p.getSort());
        return node;
    }
}
