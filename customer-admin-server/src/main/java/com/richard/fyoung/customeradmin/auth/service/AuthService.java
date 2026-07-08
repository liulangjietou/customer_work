package com.richard.fyoung.customeradmin.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.dto.ChangePasswordRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginResponse;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 登录/登出/改密。
 *
 * <p>登录（成功与失败）均需记录操作日志（需求文档 §4.3），但失败时尚无 Sa-Token 登录态，
 * 通用的 {@code OperationLogAspect}（依赖 {@code StpUtil.isLogin()} 解析操作人）无法覆盖这个场景，
 * 故本类直接调用 {@link OperationLogMapper} 记录，不复用 AOP 切面（登出/改密走已登录路径，
 * 使用 {@code @OperationLog} 注解，见 {@link com.richard.fyoung.customeradmin.auth.controller.AuthController}）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 种子数据里 admin 账号的初始密码哈希——当前密码仍等于此值即视为"从未改过密"，强制改密。 */
    private static final String INITIAL_ADMIN_PASSWORD_HASH =
        "$2a$10$M7Z.8TA1.6l01JSeZRGAb.olJkoDmvk4JSX81kNlZ5rzE1LCsDCFC";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogMapper operationLogMapper;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
                       OperationLogMapper operationLogMapper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.operationLogMapper = operationLogMapper;
    }

    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()));

        boolean success = user != null
            && user.getStatus() != null && user.getStatus() == 1
            && passwordEncoder.matches(request.password(), user.getPassword());

        recordLoginLog(request.username(), user == null ? null : user.getId(), success,
            success ? null : "用户名或密码错误，或账号已禁用");

        if (!success) {
            throw new BizException(ResultCode.LOGIN_FAILED);
        }

        StpUtil.login(user.getId());
        StpUtil.getTokenSession().set("username", user.getUsername());

        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(resolveClientIp());
        userMapper.updateById(user);

        boolean forceChangePassword = INITIAL_ADMIN_PASSWORD_HASH.equals(user.getPassword());
        return new LoginResponse(StpUtil.getTokenValue(), user.getNickname(), forceChangePassword);
    }

    public void logout() {
        StpUtil.logout();
    }

    public void changePassword(ChangePasswordRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BizException(ResultCode.PARAM_INVALID, "原密码不正确");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userMapper.updateById(user);
        log.info("password changed, userId={}", userId);
    }

    private void recordLoginLog(String username, Long userId, boolean success, String errorMsg) {
        try {
            SysOperationLog entity = new SysOperationLog();
            entity.setUserId(userId);
            entity.setUsername(username);
            entity.setOperation(success ? "登录" : "登录失败");
            entity.setMethod("AuthController#login");
            entity.setTarget("sys_user");
            entity.setResult(success ? 1 : 0);
            entity.setErrorMsg(errorMsg);
            entity.setIp(resolveClientIp());
            entity.setCreateTime(LocalDateTime.now());
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("record login log failed, code={}", "LOGIN-LOG-RECORD-FAIL", e);
        }
    }

    private String resolveClientIp() {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
