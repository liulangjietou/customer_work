package com.richard.fyoung.customeradmin.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * "某个用户挂着哪些启用中的角色"——鉴权与数据权限共用的唯一答案。
 *
 * <p>原本这段逻辑私有在 {@code AdminStpInterfaceImpl} 里，数据权限也要用它。抽出来共用而不是
 * 各写一份：两处对同一个用户算出不同的角色集合，会表现为"有权限进页面但一条数据都看不到"
 * 这类无从下手的故障。</p>
 *
 * <p>查询固定在<b>用户归属租户</b>下进行，而不是当前视角租户。控制面用户切到租户 X 的视角后，
 * 自己的角色仍在原归属租户里，按视角租户查会一条都查不到，当场失去全部权限。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class UserRoleResolver {

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;

    public UserRoleResolver(SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 查用户的启用角色。
     *
     * <p>未登录（如 Sa-Token 在解析阶段回调）时归属租户为空，此时不切上下文，沿用调用方的现状。</p>
     */
    public List<SysRole> enabledRolesOf(Object loginId) {
        String userTenant = TenantSession.currentUserTenant();
        if (userTenant == null) {
            return doQuery(loginId);
        }
        return TenantContext.callWith(userTenant, () -> doQuery(loginId));
    }

    private List<SysRole> doQuery(Object loginId) {
        Long userId = Long.valueOf(loginId.toString());
        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream().map(SysUserRole::getRoleId).toList();
        if (CollectionUtils.isEmpty(roleIds)) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
            .filter(r -> r.getStatus() != null && r.getStatus() == 1)
            .toList();
    }
}
