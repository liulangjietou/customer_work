package com.richard.fyoung.customeradmin.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuReorderRequest;
import com.richard.fyoung.customeradmin.system.menu.service.MenuChangeLogService;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionSaveRequest;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionVO;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限/菜单树管理（需求文档"二、菜单规划"静态菜单来源）。
 *
 * <p>菜单管理页面的"改动即时生效，发布=广播通知"模型：{@link #create}/{@link #update}/
 * {@link #delete}/{@link #reorder} 都直接落库、立刻对"重新拉一次树"的人可见；但不会主动
 * {@link MenuVersionHolder#bump()}——真正让其它在线用户前端轮询感知到变化、自动刷新菜单，
 * 要等管理员编辑完一批改动后显式调 {@link #publish()} 广播一次，避免半成品改动中途闪现给
 * 其他在线用户。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class PermissionService {

    private static final long ROOT_PARENT_ID = 0L;
    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_MOVE = "MOVE";
    private static final String DEFAULT_ICON_TYPE = "library";

    private final SysPermissionMapper permissionMapper;
    private final MenuChangeLogService changeLogService;
    private final MenuVersionHolder menuVersionHolder;

    public PermissionService(SysPermissionMapper permissionMapper, MenuChangeLogService changeLogService,
                             MenuVersionHolder menuVersionHolder) {
        this.permissionMapper = permissionMapper;
        this.changeLogService = changeLogService;
        this.menuVersionHolder = menuVersionHolder;
    }

    /** 全量权限树，按 sort 升序。 */
    public List<PermissionVO> tree() {
        List<SysPermission> all = permissionMapper.selectList(null);
        Map<Long, PermissionVO> nodeById = new LinkedHashMap<>();
        for (SysPermission p : all) {
            nodeById.put(p.getId(), toVo(p));
        }
        List<PermissionVO> roots = new java.util.ArrayList<>();
        for (SysPermission p : all) {
            PermissionVO node = nodeById.get(p.getId());
            if (p.getParentId() == null || p.getParentId() == ROOT_PARENT_ID) {
                roots.add(node);
            } else {
                PermissionVO parent = nodeById.get(p.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);   // 父节点缺失（数据异常）时兜底挂根，不丢数据
                }
            }
        }
        sortTree(roots);
        return roots;
    }

    public void create(PermissionSaveRequest request) {
        if (permissionMapper.exists(new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getPermCode, request.permCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "权限标识已存在");
        }
        SysPermission p = new SysPermission();
        fillFromRequest(p, request);
        permissionMapper.insert(p);
        changeLogService.record(p.getId(), ACTION_CREATE, null, p);
    }

    public void update(Long id, PermissionSaveRequest request) {
        SysPermission before = requirePermission(id);
        SysPermission after = copyOf(before);
        fillFromRequest(after, request);
        permissionMapper.updateById(after);
        changeLogService.record(id, ACTION_UPDATE, before, after);
    }

    public void delete(Long id) {
        SysPermission before = requirePermission(id);
        boolean hasChildren = permissionMapper.exists(
            new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getParentId, id));
        if (hasChildren) {
            throw new BizException(ResultCode.PARAM_INVALID, "该节点下还有子节点，请先删除子节点");
        }
        permissionMapper.deleteById(id);
        changeLogService.record(id, ACTION_DELETE, before, null);
    }

    /** 拖拽排序：逐条更新受影响节点的 parentId/sort；只对 parentId 真变化的节点记 MOVE 流水（同层纯调顺序不算移动）。 */
    @Transactional
    public void reorder(MenuReorderRequest request) {
        for (MenuReorderRequest.Item item : request.items()) {
            SysPermission before = requirePermission(item.id());
            boolean parentChanged = !before.getParentId().equals(item.parentId());
            SysPermission after = copyOf(before);
            after.setParentId(item.parentId());
            after.setSort(item.sort());
            permissionMapper.updateById(after);
            if (parentChanged) {
                changeLogService.record(item.id(), ACTION_MOVE, before, after);
            }
        }
    }

    /** 广播菜单版本变化，让其它在线用户的前端轮询感知到并自动刷新（"发布"按钮语义）。 */
    public void publish() {
        menuVersionHolder.bump();
    }

    private void fillFromRequest(SysPermission p, PermissionSaveRequest request) {
        p.setParentId(request.parentId() == null ? ROOT_PARENT_ID : request.parentId());
        p.setPermName(request.permName());
        p.setPermCode(request.permCode());
        p.setType(request.type());
        p.setPath(request.path());
        p.setIcon(request.icon());
        p.setIconType(request.iconType() == null ? DEFAULT_ICON_TYPE : request.iconType());
        p.setSort(request.sort() == null ? 0 : request.sort());
    }

    private SysPermission copyOf(SysPermission source) {
        SysPermission copy = new SysPermission();
        copy.setId(source.getId());
        copy.setParentId(source.getParentId());
        copy.setPermName(source.getPermName());
        copy.setPermCode(source.getPermCode());
        copy.setType(source.getType());
        copy.setPath(source.getPath());
        copy.setIcon(source.getIcon());
        copy.setIconType(source.getIconType());
        copy.setSort(source.getSort());
        return copy;
    }

    private void sortTree(List<PermissionVO> nodes) {
        nodes.sort(Comparator.comparing(PermissionVO::getSort, Comparator.nullsLast(Integer::compareTo)));
        for (PermissionVO node : nodes) {
            sortTree(node.getChildren());
        }
    }

    private PermissionVO toVo(SysPermission p) {
        PermissionVO vo = new PermissionVO();
        vo.setId(p.getId());
        vo.setParentId(p.getParentId());
        vo.setPermName(p.getPermName());
        vo.setPermCode(p.getPermCode());
        vo.setType(p.getType());
        vo.setPath(p.getPath());
        vo.setIcon(p.getIcon());
        vo.setIconType(p.getIconType());
        vo.setSort(p.getSort());
        return vo;
    }

    private SysPermission requirePermission(Long id) {
        SysPermission p = permissionMapper.selectById(id);
        if (p == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "权限节点不存在: " + id);
        }
        return p;
    }
}
