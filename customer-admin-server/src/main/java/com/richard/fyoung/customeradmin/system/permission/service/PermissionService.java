package com.richard.fyoung.customeradmin.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionSaveRequest;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionVO;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限/菜单树管理（需求文档"二、菜单规划"静态菜单来源）。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PermissionService {

    private static final long ROOT_PARENT_ID = 0L;

    private final SysPermissionMapper permissionMapper;

    public PermissionService(SysPermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
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
    }

    public void update(Long id, PermissionSaveRequest request) {
        SysPermission p = requirePermission(id);
        fillFromRequest(p, request);
        permissionMapper.updateById(p);
    }

    public void delete(Long id) {
        requirePermission(id);
        boolean hasChildren = permissionMapper.exists(
            new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getParentId, id));
        if (hasChildren) {
            throw new BizException(ResultCode.PARAM_INVALID, "该节点下还有子节点，请先删除子节点");
        }
        permissionMapper.deleteById(id);
    }

    private void fillFromRequest(SysPermission p, PermissionSaveRequest request) {
        p.setParentId(request.parentId() == null ? ROOT_PARENT_ID : request.parentId());
        p.setPermName(request.permName());
        p.setPermCode(request.permCode());
        p.setType(request.type());
        p.setPath(request.path());
        p.setIcon(request.icon());
        p.setSort(request.sort() == null ? 0 : request.sort());
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
