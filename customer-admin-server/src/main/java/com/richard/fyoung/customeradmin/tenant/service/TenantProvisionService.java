package com.richard.fyoung.customeradmin.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customerwork.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 新租户开通：建一个租户内的全权管理员角色，并授予除平台专属之外的全部权限点。
 *
 * <p>不做则新租户是个空壳——没有任何角色可分配，租户管理员建了也用不了。</p>
 *
 * <p><b>为什么是"新建角色 + 授权限"而不是"复制平台角色"</b>：平台侧的角色是按运营方职责划分的
 * （超管、运维、只读等），对租户没有意义；租户要的是"我这边的管理员"。复制反而会把
 * 平台的角色语义连同其权限一起漏给租户。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class TenantProvisionService {

    /** 租户内建管理员角色编码。 */
    public static final String TENANT_ADMIN_ROLE_CODE = "tenant_admin";

    /**
     * 平台专属权限前缀：租户管理本身只能由运营方操作，绝不能授给租户管理员，
     * 否则租户能看到、甚至冻结别的租户。
     */
    private static final String PLATFORM_ONLY_PERM_PREFIX = "tenant:";

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;

    public TenantProvisionService(SysRoleMapper roleMapper,
                                  SysRolePermissionMapper rolePermissionMapper,
                                  SysPermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    /**
     * 为指定租户初始化内建角色。
     *
     * <p>整段跑在目标租户的上下文里，插入 {@code sys_role} / {@code sys_role_permission} 时
     * 由租户拦截器自动补 {@code tenant_id}——这样就不需要在实体上挂租户字段、也不会写错归属。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void provision(String tenantCode) {
        // sys_permission 是平台级表（不参与租户过滤），在哪个上下文里读都是同一份
        List<SysPermission> permissions = permissionMapper.selectList(new LambdaQueryWrapper<>());
        List<Long> grantablePermissionIds = permissions.stream()
            .filter(p -> p.getPermCode() == null || !p.getPermCode().startsWith(PLATFORM_ONLY_PERM_PREFIX))
            .map(SysPermission::getId)
            .toList();

        TenantContext.runWith(tenantCode, () -> {
            SysRole existing = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, TENANT_ADMIN_ROLE_CODE));
            if (existing != null) {
                log.info("tenant admin role already exists, skip provision, tenant={}", tenantCode);
                return;
            }

            SysRole role = new SysRole();
            role.setRoleName("租户管理员");
            role.setRoleCode(TENANT_ADMIN_ROLE_CODE);
            role.setRemark("租户开通时自动创建，拥有本租户内全部管理权限");
            role.setStatus(1);
            roleMapper.insert(role);

            if (CollectionUtils.isEmpty(grantablePermissionIds)) {
                log.info("no grantable permission found, tenant={}, roleId={}", tenantCode, role.getId());
                return;
            }
            for (Long permissionId : grantablePermissionIds) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(role.getId());
                rp.setPermissionId(permissionId);
                rolePermissionMapper.insert(rp);
            }
            log.info("tenant provisioned, tenant={}, roleId={}, permissionCount={}",
                tenantCode, role.getId(), grantablePermissionIds.size());
        });
    }
}
