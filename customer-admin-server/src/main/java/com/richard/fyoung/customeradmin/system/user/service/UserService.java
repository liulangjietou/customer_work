package com.richard.fyoung.customeradmin.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserVO;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用户管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final CrossTenantAuthority crossTenantAuthority;

    public UserService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       SysRoleMapper roleMapper, PasswordEncoder passwordEncoder,
                       CrossTenantAuthority crossTenantAuthority) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    public PageResult<UserVO> page(PageQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(SysUser::getUsername, query.getKeyword())
                .or().like(SysUser::getNickname, query.getKeyword()));
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysUser::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), SysUser::getCreateTime);

        IPage<SysUser> page = userMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        IPage<UserVO> voPage = page.convert(this::toVoWithoutRoles);
        fillRoles(voPage.getRecords());
        return PageResult.of(voPage);
    }

    public UserVO get(Long id) {
        SysUser user = requireUser(id);
        UserVO vo = toVoWithoutRoles(user);
        fillRoles(List.of(vo));
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(UserSaveRequest request) {
        if (!StringUtils.hasText(request.password())) {
            throw new BizException(ResultCode.PARAM_MISSING, "新建用户必须设置初始密码");
        }
        if (userMapper.exists(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "用户名已存在");
        }
        List<Long> roleIds = validateRoleChange(null, request.roleIds());
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setStatus(request.status() == null ? 1 : request.status());
        userMapper.insert(user);
        replaceRoles(user.getId(), roleIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UserSaveRequest request) {
        SysUser user = requireUser(id);
        List<Long> roleIds = validateRoleChange(id, request.roleIds());
        user.setNickname(request.nickname());
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (StringUtils.hasText(request.password())) {
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        userMapper.updateById(user);
        replaceRoles(id, roleIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireUser(id);
        guardExistingControlPlaneAssignment(id);
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
    }

    private void replaceRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (CollectionUtils.isEmpty(roleIds)) {
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }

    /**
     * 角色分配在改用户数据前完成校验，避免普通 default 用户把控制面角色分给自己，
     * 也避免校验失败后留下已改用户、未改角色的半完成状态。
     */
    private List<Long> validateRoleChange(Long userId, List<Long> requestedRoleIds) {
        if (CollectionUtils.isEmpty(requestedRoleIds)) {
            guardExistingControlPlaneAssignment(userId);
            return List.of();
        }
        List<Long> roleIds = requestedRoleIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (roleIds.size() != requestedRoleIds.size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "角色 ID 不能为空或重复");
        }
        List<SysRole> requestedRoles = roleMapper.selectBatchIds(roleIds);
        if (requestedRoles.size() != roleIds.size()) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "角色不存在或不属于当前租户");
        }
        boolean touchesControlPlane = crossTenantAuthority.hasAuthority(requestedRoles)
            || hasExistingControlPlaneAssignment(userId);
        if (touchesControlPlane && !crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以分配或移除控制面角色");
        }
        return roleIds;
    }

    private void guardExistingControlPlaneAssignment(Long userId) {
        if (hasExistingControlPlaneAssignment(userId)
            && !crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以分配或移除控制面角色");
        }
    }

    private boolean hasExistingControlPlaneAssignment(Long userId) {
        if (userId == null) {
            return false;
        }
        List<Long> existingRoleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream()
            .map(SysUserRole::getRoleId)
            .distinct()
            .toList();
        if (existingRoleIds.isEmpty()) {
            return false;
        }
        return crossTenantAuthority.hasAuthority(roleMapper.selectBatchIds(existingRoleIds));
    }

    private void fillRoles(List<UserVO> users) {
        if (users.isEmpty()) {
            return;
        }
        List<Long> userIds = users.stream().map(UserVO::getId).toList();
        List<SysUserRole> relations = userRoleMapper.selectList(
            new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds));
        if (relations.isEmpty()) {
            return;
        }
        List<Long> roleIds = relations.stream().map(SysUserRole::getRoleId).distinct().toList();
        Map<Long, String> roleNameById = roleMapper.selectBatchIds(roleIds).stream()
            .collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));
        Map<Long, List<SysUserRole>> relationsByUser = relations.stream()
            .collect(Collectors.groupingBy(SysUserRole::getUserId));

        for (UserVO vo : users) {
            List<SysUserRole> userRelations = relationsByUser.getOrDefault(vo.getId(), Collections.emptyList());
            vo.setRoleIds(userRelations.stream().map(SysUserRole::getRoleId).toList());
            vo.setRoleNames(userRelations.stream()
                .map(r -> roleNameById.get(r.getRoleId()))
                .filter(java.util.Objects::nonNull)
                .toList());
        }
    }

    private UserVO toVoWithoutRoles(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setCreateTime(user.getCreateTime());
        vo.setRoleIds(List.of());
        vo.setRoleNames(List.of());
        return vo;
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "用户不存在: " + id);
        }
        return user;
    }
}
