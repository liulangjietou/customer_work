package com.richard.fyoung.customeradmin.system.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserPageQuery;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserVO;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
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

    private static final String DUPLICATE_USERNAME_MESSAGE = "用户名已存在";

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final CrossTenantAuthority crossTenantAuthority;
    private final SessionRevocationService sessionRevocationService;

    public UserService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       SysRoleMapper roleMapper, PasswordEncoder passwordEncoder,
                       CrossTenantAuthority crossTenantAuthority,
                       SessionRevocationService sessionRevocationService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.crossTenantAuthority = crossTenantAuthority;
        this.sessionRevocationService = sessionRevocationService;
    }

    public PageResult<UserVO> page(UserPageQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(SysUser::getUsername, query.getKeyword())
                .or().like(SysUser::getNickname, query.getKeyword()));
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysUser::getStatus, query.getStatus());
        }
        if (query.getApprovalStatus() != null) {
            wrapper.eq(SysUser::getApprovalStatus, query.getApprovalStatus().name());
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
            throw duplicateUsernameException();
        }
        List<Long> roleIds = validateRoleChange(null, request.roleIds());
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setStatus(request.status() == null ? 1 : request.status());
        user.setApprovalStatus(UserApprovalStatus.APPROVED.name());
        // exists() 只能减少常规冲突，无法覆盖并发创建，也看不到被逻辑删除但仍占唯一键的用户。
        // 数据库唯一约束是最终防线；这里将其转换为稳定业务错误，供前端统一请求拦截器直接提示。
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw duplicateUsernameException();
        }
        replaceRoles(user.getId(), roleIds);
    }

    private BizException duplicateUsernameException() {
        return new BizException(ResultCode.RESOURCE_DUPLICATE, DUPLICATE_USERNAME_MESSAGE);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UserSaveRequest request) {
        SysUser user = requireUser(id);
        List<Long> roleIds = validateRoleChange(id, request.roleIds());
        if (!UserApprovalStatus.parse(user.getApprovalStatus()).allowsPermissions()
            && !roleIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "待审核或已拒绝用户请通过审核操作分配角色");
        }
        List<Long> previousRoleIds = existingRoleIds(id);
        boolean statusChanged = request.status() != null && !Objects.equals(user.getStatus(), request.status());
        boolean passwordChanged = StringUtils.hasText(request.password());
        boolean rolesChanged = !new HashSet<>(previousRoleIds).equals(new HashSet<>(roleIds));
        user.setNickname(request.nickname());
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (StringUtils.hasText(request.password())) {
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        userMapper.updateById(user);
        replaceRoles(id, roleIds);
        if (statusChanged || passwordChanged || rolesChanged) {
            requireEpochIncremented(userMapper.incrementAuthEpoch(id));
            sessionRevocationService.revokeUserAfterCommit(id);
        }
    }

    /**
     * 审核自助注册用户。批准与角色分配在同一事务完成，拒绝则清空角色；两种决策都会撤销旧会话，
     * 让用户重新登录后读取新的审核状态和权限集合。
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(Long id, UserApprovalRequest request) {
        if (request.decision() == UserApprovalStatus.PENDING) {
            throw new BizException(ResultCode.PARAM_INVALID, "审核结果只能是通过或拒绝");
        }
        SysUser user = requireUser(id);
        List<Long> previousRoleIds = existingRoleIds(id);
        List<Long> roleIds;
        if (request.decision() == UserApprovalStatus.APPROVED) {
            roleIds = validateRoleChange(id, request.roleIds());
            if (roleIds.isEmpty()) {
                throw new BizException(ResultCode.PARAM_MISSING, "审核通过时必须至少分配一个角色");
            }
        } else {
            roleIds = validateRoleChange(id, List.of());
        }

        boolean approvalChanged = request.decision()
            != UserApprovalStatus.parse(user.getApprovalStatus());
        boolean rolesChanged = !new HashSet<>(previousRoleIds).equals(new HashSet<>(roleIds));
        user.setApprovalStatus(request.decision().name());
        user.setApprovalBy(StpUtil.getLoginIdAsLong());
        user.setApprovalTime(LocalDateTime.now());
        user.setApprovalRemark(StringUtils.hasText(request.remark()) ? request.remark().trim() : null);
        userMapper.updateById(user);
        replaceRoles(id, roleIds);

        if (approvalChanged || rolesChanged) {
            requireEpochIncremented(userMapper.incrementAuthEpoch(id));
            sessionRevocationService.revokeUserAfterCommit(id);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireUser(id);
        guardExistingControlPlaneAssignment(id);
        requireEpochIncremented(userMapper.incrementAuthEpoch(id));
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        sessionRevocationService.revokeUserAfterCommit(id);
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

    private List<Long> existingRoleIds(Long userId) {
        return userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream()
            .map(SysUserRole::getRoleId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private void requireEpochIncremented(int changed) {
        if (changed != 1) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "用户不存在或已删除");
        }
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
        vo.setApprovalStatus(UserApprovalStatus.parse(user.getApprovalStatus()).name());
        vo.setApprovalBy(user.getApprovalBy());
        vo.setApprovalTime(user.getApprovalTime());
        vo.setApprovalRemark(user.getApprovalRemark());
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
