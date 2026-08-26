package com.richard.fyoung.customeradmin.system.user.service;

import com.richard.fyoung.customeradmin.auth.dto.RegisterRequest;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 后台本地账号自助注册。
 *
 * <p>公开注册只创建最小权限账号：固定归入 {@code default} 租户、审核状态为 PENDING、
 * 不写任何用户角色关系。用户名全局唯一，已软删除的同名账号也不能被匿名注册者复活。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);
    private static final String LOCAL_LOGIN_TYPE = "LOCAL";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserRegistrationService(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new BizException(ResultCode.PARAM_INVALID, "两次输入的密码不一致");
        }
        String username = request.username().trim();
        SysUser occupied = CrossTenantOperations.execute(
            () -> userMapper.selectByUsernameIgnoreLogicDelete(username));
        if (occupied != null) {
            throw duplicateUsername();
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setTenantId(TenantContext.DEFAULT);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname().trim() : username);
        user.setLoginType(LOCAL_LOGIN_TYPE);
        user.setStatus(1);
        user.setApprovalStatus(UserApprovalStatus.PENDING.name());

        try {
            TenantContext.runWith(TenantContext.DEFAULT, () -> userMapper.insert(user));
        } catch (DuplicateKeyException e) {
            // 预查与插入之间仍可能有并发注册，数据库唯一键是最终事实来源。
            throw duplicateUsername();
        }
        log.info("self-registration created pending admin account, userId={}, username={}",
            user.getId(), username);
    }

    private BizException duplicateUsername() {
        return new BizException(ResultCode.RESOURCE_DUPLICATE, "用户名已存在");
    }
}
