package com.richard.fyoung.customeradmin.auth.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.config.AdminLdapProperties;
import com.richard.fyoung.customeradmin.auth.dto.ChangePasswordRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginRequest;
import com.richard.fyoung.customeradmin.auth.dto.LoginResponse;
import com.richard.fyoung.customeradmin.auth.dto.SsoLoginRequest;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

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
    private final LdapAuthService ldapAuthService;
    private final AdminLdapProperties ldapProperties;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;

    /** “记住我”勾选后登录态有效期（秒），默认 7 天；不勾选时沿用 sa-token.timeout（2 小时）全局配置。 */
    @Value("${admin.remember-me-timeout-seconds:604800}")
    private long rememberMeTimeoutSeconds;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
                       OperationLogMapper operationLogMapper, LdapAuthService ldapAuthService,
                       AdminLdapProperties ldapProperties, SysRoleMapper roleMapper,
                       SysUserRoleMapper userRoleMapper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.operationLogMapper = operationLogMapper;
        this.ldapAuthService = ldapAuthService;
        this.ldapProperties = ldapProperties;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
    }

    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.username()));

        boolean success = user != null
            && user.getStatus() != null && user.getStatus() == 1
            && passwordEncoder.matches(request.password(), user.getPassword());

        recordLoginLog(request.username(), user == null ? null : user.getId(), success,
            success ? null : "用户名或密码错误，或账号已禁用",
            success ? "登录" : "登录失败", "AuthController#login");

        if (!success) {
            throw new BizException(ResultCode.LOGIN_FAILED);
        }

        doLogin(user, request.rememberMe());

        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(resolveClientIp());
        userMapper.updateById(user);

        boolean forceChangePassword = INITIAL_ADMIN_PASSWORD_HASH.equals(user.getPassword());
        return new LoginResponse(StpUtil.getTokenValue(), user.getNickname(), forceChangePassword);
    }

    /**
     * OA 域账号（LDAP/AD）单点登录。
     *
     * <p>密码本身交由企业 AD 域控校验（{@link LdapAuthService}），本方法不存不比对密码；
     * 首次登录自动在 {@code sys_user} 创建本地影子账号（{@code login_type=LDAP}）并按配置默认角色，
     * 后继开始复用同一行、不重复创建。</p>
     */
    public LoginResponse ssoLogin(SsoLoginRequest request) {
        if (!ldapProperties.isEnabled()) {
            throw new BizException(ResultCode.SSO_NOT_ENABLED);
        }
        String username = normalizeLdapUsername(request.username());

        LdapBindResult bindResult = ldapAuthService.bind(username, request.password());
        if (bindResult == LdapBindResult.SERVICE_UNAVAILABLE) {
            recordLoginLog(username, null, false, "OA域服务不可用",
                "OA登录失败", "AuthController#ssoLogin");
            throw new BizException(ResultCode.SSO_SERVICE_UNAVAILABLE);
        }
        if (bindResult == LdapBindResult.INVALID_CREDENTIALS) {
            recordLoginLog(username, null, false, "OA账号或密码错误",
                "OA登录失败", "AuthController#ssoLogin");
            throw new BizException(ResultCode.SSO_LOGIN_FAILED);
        }

        SysUser user = findOrCreateLdapUser(username);
        if (user.getStatus() == null || user.getStatus() != 1) {
            recordLoginLog(username, user.getId(), false, "账号已禁用",
                "OA登录失败", "AuthController#ssoLogin");
            throw new BizException(ResultCode.SSO_LOGIN_FAILED, "账号已被禁用，请联系管理员");
        }

        recordLoginLog(username, user.getId(), true, null, "OA登录", "AuthController#ssoLogin");

        doLogin(user, request.rememberMe());

        user.setLastLoginTime(LocalDateTime.now());
        user.setLastLoginIp(resolveClientIp());
        userMapper.updateById(user);

        // LDAP 账号密码由企业域控统一管理，不走本地初始密码强制改密逻辑
        return new LoginResponse(StpUtil.getTokenValue(), user.getNickname(), false);
    }

    public void logout() {
        StpUtil.logout();
    }

    /** 勾选“记住我”时用 {@code rememberMeTimeoutSeconds} 覆盖登录态有效期，否则沿用 sa-token.timeout 全局配置。 */
    private void doLogin(SysUser user, Boolean rememberMe) {
        if (Boolean.TRUE.equals(rememberMe)) {
            StpUtil.login(user.getId(), SaLoginModel.create().setTimeout(rememberMeTimeoutSeconds));
        } else {
            StpUtil.login(user.getId());
        }
        StpUtil.getTokenSession().set("username", user.getUsername());
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

    private void recordLoginLog(String username, Long userId, boolean success, String errorMsg,
                                 String operation, String method) {
        try {
            SysOperationLog entity = new SysOperationLog();
            entity.setUserId(userId);
            entity.setUsername(username);
            entity.setOperation(operation);
            entity.setMethod(method);
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

    /** 用户可直接输入 RichardFyoung 或 RichardFyoung@xxx，统一取 @ 前半部分再拼接配置的域名后缀发起 Bind。 */
    private String normalizeLdapUsername(String rawUsername) {
        String trimmed = rawUsername.trim();
        int at = trimmed.indexOf('@');
        return at > 0 ? trimmed.substring(0, at) : trimmed;
    }

    private SysUser findOrCreateLdapUser(String username) {
        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user != null) {
            return user;
        }

        // 坑：sys_user.uk_sys_user_username 是纯数据库唯一约束，不包含 deleted 列。该用户名如果曾被
        // 管理员删除过（UserService#delete 走的是逻辑删除，deleted 置 1），上面的 selectOne 会因
        // MyBatis-Plus 自动拼接的 deleted=0 过滤而查不到，但直接 INSERT 会因用户名被旧行占住而报
        // DuplicateKeyException（已实测复现）。这里先查有没有被软删除过的旧行，有就“复活”它而不是插新行；
        // 即使确实无旧行（纯并发竞争），下面 insert 仍包 catch DuplicateKeyException 兼底。
        SysUser deletedUser = userMapper.selectByUsernameIgnoreLogicDelete(username);
        if (deletedUser != null) {
            userMapper.reviveDeletedUser(deletedUser.getId(), username);
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, deletedUser.getId()));
            assignDefaultRoles(deletedUser.getId());
            log.info("revived soft-deleted local account for LDAP sso login, username={}, userId={}",
                username, deletedUser.getId());
            return userMapper.selectById(deletedUser.getId());
        }

        SysUser created = new SysUser();
        created.setUsername(username);
        created.setPassword(null);
        created.setNickname(username);
        created.setLoginType("LDAP");
        created.setStatus(1);
        try {
            userMapper.insert(created);
        } catch (DuplicateKeyException e) {
            // 坑：高并发下两个请求同时到这里，上面的查询都没命中另一个已提交的 INSERT，先插那个提交了、
            // 后插的就会撞唯一约束；这时其实对方已经建好了号，重新查一次拿那个已存在的行即可，不需要重试插入。
            SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        assignDefaultRoles(created.getId());
        log.info("auto-created local account for LDAP sso login, username={}, userId={}", username, created.getId());
        return created;
    }

    private void assignDefaultRoles(Long userId) {
        List<String> roleCodes = ldapProperties.getDefaultRoleCodes();
        if (CollectionUtils.isEmpty(roleCodes)) {
            return;
        }
        List<SysRole> roles = roleMapper.selectList(
            new LambdaQueryWrapper<SysRole>().in(SysRole::getRoleCode, roleCodes));
        for (SysRole role : roles) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
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
