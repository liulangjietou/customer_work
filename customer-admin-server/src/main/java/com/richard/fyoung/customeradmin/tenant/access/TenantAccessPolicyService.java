package com.richard.fyoung.customeradmin.tenant.access;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 后台会话的用户状态、租户状态及版本校验唯一入口。 */
@Service
public class TenantAccessPolicyService {

    private final SysUserMapper userMapper;
    private final TenantService tenantService;

    public TenantAccessPolicyService(SysUserMapper userMapper, TenantService tenantService) {
        this.userMapper = userMapper;
        this.tenantService = tenantService;
    }

    /** 校验用户自身归属及登录时保存的双 epoch；任一不一致都必须重新登录。 */
    public void assertUserSessionAccessible(Long userId, String tenantId,
                                            Long expectedAuthEpoch, Long expectedTenantEpoch) {
        if (userId == null || expectedAuthEpoch == null || expectedTenantEpoch == null) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        }
        SysUser user = TenantContext.callWith(tenantId, () -> userMapper.selectById(userId));
        boolean invalidUser = user == null
            || user.getStatus() == null || user.getStatus() != 1
            || !TenantContext.sameTenant(tenantId, user.getTenantId())
            || user.getAuthEpoch() == null || !expectedAuthEpoch.equals(user.getAuthEpoch());
        if (invalidUser) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        }
        assertTenantAccessible(tenantId, expectedTenantEpoch);
    }

    /** 校验控制面当前视角的租户状态和版本；不涉及用户自身的登录态。 */
    public void assertTenantAccessible(String tenantId, Long expectedTenantEpoch) {
        if (expectedTenantEpoch == null) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        }
        TenantAccessSnapshot snapshot = tenantService.requireAccessibleSnapshot(tenantId);
        if (snapshot.accessEpoch() != expectedTenantEpoch) {
            throw new BizException(ResultCode.TOKEN_EXPIRED);
        }
    }
}
