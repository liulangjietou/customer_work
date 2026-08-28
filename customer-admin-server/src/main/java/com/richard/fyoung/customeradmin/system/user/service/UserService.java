package com.richard.fyoung.customeradmin.system.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.notify.RegistrationNotificationService;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalOptionsVO;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserPageQuery;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserVO;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.dto.TenantSaveRequest;
import com.richard.fyoung.customeradmin.tenant.dto.TenantVO;
import com.richard.fyoung.customeradmin.tenant.service.TenantProvisionService;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
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
    private final TenantService tenantService;
    private final PublicDeploymentProperties publicDeployment;
    private final RegistrationNotificationService registrationNotificationService;

    public UserService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       SysRoleMapper roleMapper, PasswordEncoder passwordEncoder,
                       CrossTenantAuthority crossTenantAuthority,
                       SessionRevocationService sessionRevocationService,
                       TenantService tenantService,
                       PublicDeploymentProperties publicDeployment,
                       RegistrationNotificationService registrationNotificationService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.crossTenantAuthority = crossTenantAuthority;
        this.sessionRevocationService = sessionRevocationService;
        this.tenantService = tenantService;
        this.publicDeployment = publicDeployment;
        this.registrationNotificationService = registrationNotificationService;
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

    /**
     * 返回注册审核可选租户及目标租户内的启用角色。
     *
     * <p>普通租户审核人只能看到当前租户；具备控制面能力的审核人才能跨租户选择。角色查询临时进入
     * 目标租户上下文，前端无需切换整个管理后台视角，也不能通过伪造 tenantId 越权枚举其它租户角色。</p>
     */
    public UserApprovalOptionsVO approvalOptions(String requestedTenantId) {
        String currentTenant = TenantContext.require();
        boolean canCrossTenant = crossTenantAuthority.hasCurrentUserAuthority();
        List<TenantVO> activeTenants = tenantService.listActive().stream()
            .filter(tenant -> tenant.getExpireTime() == null
                || !tenant.getExpireTime().isBefore(LocalDateTime.now()))
            .toList();
        List<TenantVO> visibleTenants = canCrossTenant
            ? activeTenants
            : activeTenants.stream()
                .filter(tenant -> TenantContext.sameTenant(currentTenant, tenant.getTenantCode()))
                .toList();

        String targetInput = StringUtils.hasText(requestedTenantId)
            ? requestedTenantId.trim()
            : currentTenant;
        if (!TenantContext.sameTenant(currentTenant, targetInput) && !canCrossTenant) {
            // 先做越权判定再查租户主数据，避免普通租户用户借错误码枚举其它租户是否存在。
            throw new BizException(ResultCode.TENANT_VIEW_FORBIDDEN);
        }
        TenantAccessSnapshot targetSnapshot = tenantService.resolveAccessibleSnapshot(targetInput);
        if (targetSnapshot == null) {
            throw new BizException(ResultCode.TENANT_NOT_FOUND);
        }
        String targetTenant = targetSnapshot.tenantId();

        List<UserApprovalOptionsVO.RoleOption> roles = TenantContext.callWith(targetTenant,
            () -> roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getStatus, 1)
                    .orderByAsc(SysRole::getRoleName))
                .stream()
                .map(role -> new UserApprovalOptionsVO.RoleOption(
                    role.getId(), role.getRoleName(), role.getRoleCode(),
                    crossTenantAuthority.isControlPlaneRole(role)))
                .toList());
        List<UserApprovalOptionsVO.TenantOption> tenants = visibleTenants.stream()
            .map(tenant -> new UserApprovalOptionsVO.TenantOption(
                tenant.getTenantCode(), tenant.getTenantName()))
            .toList();
        return new UserApprovalOptionsVO(targetTenant, tenants, roles);
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
     * 审核自助注册用户。批准时把用户归属、角色关系一次提交到目标租户；拒绝保持原租户并清空角色。
     * 租户或安全属性发生变化时撤销旧会话，让用户重新登录后读取新的租户与权限集合。
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(Long id, UserApprovalRequest request) {
        if (request.decision() == UserApprovalStatus.PENDING) {
            throw new BizException(ResultCode.PARAM_INVALID, "审核结果只能是通过或拒绝");
        }
        SysUser user = requireUser(id);
        String sourceTenant = StringUtils.hasText(user.getTenantId())
            ? TenantContext.canonicalizeTenantId(user.getTenantId())
            : TenantContext.require();
        // 顺带开租户要先于目标租户解析：解析的正是这一步刚建出来的那个租户
        String provisionedTenant = provisionTenantIfRequested(request, user);
        String targetTenant = provisionedTenant != null
            ? provisionedTenant
            : resolveApprovalTenant(sourceTenant, request);
        List<Long> previousRoleIds = TenantContext.callWith(sourceTenant, () -> existingRoleIds(id));
        TenantContext.runWith(sourceTenant, () -> guardExistingControlPlaneAssignment(id));
        List<Long> roleIds;
        if (request.decision() == UserApprovalStatus.APPROVED) {
            roleIds = provisionedTenant != null
                ? tenantAdminRoleIds(provisionedTenant)
                : TenantContext.callWith(targetTenant,
                    () -> validateRoleChange(null, request.roleIds()));
            if (roleIds.isEmpty()) {
                throw new BizException(ResultCode.PARAM_MISSING, "审核通过时必须至少分配一个角色");
            }
        } else {
            roleIds = List.of();
        }

        boolean approvalChanged = request.decision()
            != UserApprovalStatus.parse(user.getApprovalStatus());
        boolean rolesChanged = !new HashSet<>(previousRoleIds).equals(new HashSet<>(roleIds));
        boolean tenantChanged = !TenantContext.sameTenant(sourceTenant, targetTenant);
        user.setApprovalStatus(request.decision().name());
        user.setTenantId(targetTenant);
        user.setApprovalBy(StpUtil.getLoginIdAsLong());
        user.setApprovalTime(LocalDateTime.now());
        user.setApprovalRemark(StringUtils.hasText(request.remark()) ? request.remark().trim() : null);
        int userChanged = TenantContext.callWith(sourceTenant, () -> userMapper.updateById(user));
        if (userChanged != 1) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "用户不存在或归属租户已变化");
        }
        TenantContext.runWith(sourceTenant, () -> replaceRoles(id, List.of()));
        TenantContext.runWith(targetTenant, () -> insertRoles(id, roleIds));

        if (approvalChanged || rolesChanged || tenantChanged) {
            requireEpochIncremented(TenantContext.callWith(targetTenant,
                () -> userMapper.incrementAuthEpoch(id)));
            sessionRevocationService.revokeUserAfterCommit(id);
        }
        // 待审核用户不会守着后台刷新，被拒绝的人更没有可用的后台；通知在提交后发出
        registrationNotificationService.notifyApprovalResultAfterCommit(
            user, request.decision(), request.remark(), targetTenant);
    }

    /**
     * 审核通过时顺带开通新租户。
     *
     * <p>没有这一步，对外开放实例的审核人只能把注册者塞进某个已存在的租户——现实里那就是
     * 平台自用的 {@code default}，而同一个租户内绝大多数配置资产（智能体、知识库、技能、
     * MCP、渠道、SQL 配置、字典、敏感词）是共享的，见 {@code DataScopeTables} 的类注释。
     * 陌生人一旦落进去，看到的就是平台自己的东西。</p>
     *
     * <p>开租户是控制面动作（会写 {@code sys_tenant} 并生成一个带全量租户内权限的角色），
     * 因此先要跨租户权限；{@code TenantService#create} 内部会调 provision 建好租户管理员角色。</p>
     *
     * @return 新租户编码；未请求开租户时返回 {@code null}
     */
    private String provisionTenantIfRequested(UserApprovalRequest request, SysUser user) {
        UserApprovalRequest.NewTenant newTenant = request.newTenant();
        if (newTenant == null) {
            return null;
        }
        if (request.decision() != UserApprovalStatus.APPROVED) {
            throw new BizException(ResultCode.PARAM_INVALID, "拒绝注册申请时不能开通租户");
        }
        crossTenantAuthority.requireCurrentUserAuthority();
        TenantSaveRequest tenantRequest = new TenantSaveRequest();
        tenantRequest.setTenantCode(newTenant.tenantCode().trim());
        tenantRequest.setTenantName(newTenant.tenantName().trim());
        tenantRequest.setContactName(user.getNickname());
        tenantRequest.setContactEmail(StringUtils.hasText(newTenant.contactEmail())
            ? newTenant.contactEmail().trim() : user.getEmail());
        tenantRequest.setRemark("由注册审核开通，注册账号：" + user.getUsername());
        tenantService.create(tenantRequest);
        return TenantContext.canonicalizeTenantId(tenantRequest.getTenantCode());
    }

    /**
     * 取新租户里刚生成的租户管理员角色。
     *
     * <p>不能让审核人从请求里传角色 ID：那个角色是 {@code TenantProvisionService} 在上一行
     * 刚插入的，调用方无从得知它的主键。</p>
     */
    private List<Long> tenantAdminRoleIds(String tenantCode) {
        List<Long> roleIds = TenantContext.callWith(tenantCode, () ->
            roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, TenantProvisionService.TENANT_ADMIN_ROLE_CODE))
                .stream()
                .map(SysRole::getId)
                .toList());
        if (roleIds.isEmpty()) {
            // provision 刚跑过，取不到说明租户初始化没落地，继续下去会造出一个没有任何权限的账号
            throw new BizException(ResultCode.SYSTEM_ERROR, "新租户初始化失败，未生成租户管理员角色");
        }
        return roleIds;
    }

    private String resolveApprovalTenant(String sourceTenant, UserApprovalRequest request) {
        if (request.decision() == UserApprovalStatus.REJECTED) {
            if (StringUtils.hasText(request.tenantId())
                && !TenantContext.sameTenant(sourceTenant, request.tenantId().trim())) {
                throw new BizException(ResultCode.PARAM_INVALID, "拒绝注册申请时不能变更用户归属租户");
            }
            return sourceTenant;
        }
        if (!StringUtils.hasText(request.tenantId())) {
            throw new BizException(ResultCode.PARAM_MISSING, "审核通过时必须选择归属租户");
        }
        String requestedTenant = request.tenantId().trim();
        // 对外开放实例上，default 是平台自用租户：超管在里面，模型配置、智能体、知识库等
        // 租户内共享资产也在里面。把外部注册者并进去，等于让陌生人和平台资产同处一个隔离域。
        if (publicDeployment.isEnabled() && TenantContext.isDefaultTenant(requestedTenant)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "对外开放实例不允许把注册用户并入平台租户，请选择或新开一个租户");
        }
        if (!TenantContext.sameTenant(sourceTenant, requestedTenant)) {
            // 授权判定先于目标租户查询，普通审核人不能利用错误差异枚举平台租户。
            crossTenantAuthority.requireCurrentUserAuthority();
        }
        TenantAccessSnapshot target = tenantService.resolveAccessibleSnapshot(requestedTenant);
        if (target == null) {
            throw new BizException(ResultCode.TENANT_NOT_FOUND);
        }
        return target.tenantId();
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
        insertRoles(userId, roleIds);
    }

    private void insertRoles(Long userId, List<Long> roleIds) {
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
        if (requestedRoles.stream().anyMatch(role -> !Integer.valueOf(1).equals(role.getStatus()))) {
            throw new BizException(ResultCode.PARAM_INVALID, "不能分配已停用角色");
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
        vo.setTenantId(user.getTenantId());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
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
