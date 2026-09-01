package com.richard.fyoung.customeradmin.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.auth.dto.RegisterRequest;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
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

import java.util.Locale;

/**
 * 后台本地账号自助注册。
 *
 * <p>公开注册只创建最小权限账号：固定归入 {@code default} 租户、审核状态为 PENDING、
 * 不写任何用户角色关系。用户名全局唯一，已软删除的同名账号也不能被匿名注册者复活。</p>
 *
 * <p><b>邮箱是硬前提</b>：必须填写、必须通过邮箱验证码核验，任何部署形态都一样
 * （见 {@code RegistrationGuard}）。因此落库的账号 {@code email_verified} 恒为 1，
 * 而管理员在后台预建的账号仍可以没有邮箱——那条路不经过这里。</p>
 *
 * <p><b>为什么待审核账号先落 {@code default} 租户</b>：此刻还不知道它该归谁。这一步只是
 * 待审核池，账号在 PENDING 状态下拿不到任何角色，也就拿不到任何权限点
 * （见 {@code UserApprovalStatus#allowsPermissions}）；真正的归属由审核那一步写入，
 * 对外部署还会强制要求归到 {@code default} 以外的租户，见 {@code UserService#review}。</p>
 *
 * <p><b>关于用户名枚举</b>：重名时明确提示"用户名已存在"是注册页的可用性底线，
 * 不提示会让人反复试却不知道为什么失败。批量枚举由 {@code RegistrationGuard} 的
 * IP 限流兜住（默认 5 次/小时），而登录侧本就不区分"账号不存在"与"密码错误"，
 * 攻击者拿注册接口探到的账号在登录侧也占不到便宜。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);
    private static final String LOCAL_LOGIN_TYPE = "LOCAL";
    private static final int EMAIL_VERIFIED = 1;

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationGuard registrationGuard;

    public UserRegistrationService(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
                                   RegistrationGuard registrationGuard) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.registrationGuard = registrationGuard;
    }

    /**
     * 自助注册。
     *
     * @param request  注册表单
     * @param clientIp 来源 IP，用于频率限制；由 Controller 从请求里解析后传入，
     *                 不在本类里读 {@code RequestContextHolder}——那会让这段逻辑绑死在 Web 线程上
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request, String clientIp) {
        String username = request.username().trim();
        String email = normalizeEmail(request.email());

        // 准入判定（开关/表单校验/频率/验证码）统一在 Guard 一处，这里不再重复任何一项。
        // 传归一后的邮箱：邮箱验证码是按收件人存的，大小写不同会查不到自己刚收到的那份码。
        registrationGuard.admit(clientIp, email, request.emailCode(),
            request.password(), request.confirmPassword());

        SysUser occupied = CrossTenantOperations.execute(
            () -> userMapper.selectByUsernameIgnoreLogicDelete(username));
        if (occupied != null) {
            throw duplicateUsername();
        }
        if (emailTaken(email)) {
            throw duplicateEmail();
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setTenantId(TenantContext.DEFAULT);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname().trim() : username);
        user.setEmail(email);
        // 走到这里必然过了验证码核验（Guard 无条件校验），所以此刻邮箱确实归申请人所有
        user.setEmailVerified(EMAIL_VERIFIED);
        user.setLoginType(LOCAL_LOGIN_TYPE);
        user.setStatus(1);
        user.setApprovalStatus(UserApprovalStatus.PENDING.name());

        try {
            TenantContext.runWith(TenantContext.DEFAULT, () -> userMapper.insert(user));
        } catch (DuplicateKeyException e) {
            // 预查与插入之间仍可能有并发注册，数据库唯一键是最终事实来源。
            // 用户名与邮箱各有一个唯一键，异常信息里的键名决定该报哪一个。
            throw isEmailConflict(e) ? duplicateEmail() : duplicateUsername();
        }
        log.info("self-registration created pending admin account, userId={}, username={}, emailVerified={}",
            user.getId(), username, user.getEmailVerified());
    }

    /**
     * 发送注册邮箱验证码。
     *
     * <p><b>先查占用再发信</b>：邮箱已被注册还照发不误的话，用户要等收到码、填完整张表
     * 才被告知"这邮箱已注册"，白跑一趟；更要紧的是那封信本身已经发到别人的邮箱里了。</p>
     *
     * <p>占用判定要跨租户查：待审核账号先落 {@code default}，审核通过后会被迁到目标租户，
     * 只在当前租户里查会漏掉那些已经迁走的人。</p>
     *
     * @return 验证码有效期（秒），供前端提示
     */
    public int sendEmailCode(String email, String captchaId, String captcha, String clientIp) {
        String normalized = normalizeEmail(email);
        if (normalized != null && emailTaken(normalized)) {
            throw duplicateEmail();
        }
        // normalized 为空时不在这里报错：Guard 的 sendEmailCode 会给出统一口径的"请填写注册邮箱"
        return registrationGuard.sendEmailCode(normalized, captchaId, captcha, clientIp);
    }

    /**
     * 邮箱占用判定要跨租户查：待审核账号先落 {@code default}，审核通过后会被迁到目标租户，
     * 只在当前租户里查会漏掉那些已经迁走的人，让同一个邮箱注册出第二个账号。
     */
    private boolean emailTaken(String email) {
        Long count = CrossTenantOperations.execute(() ->
            userMapper.selectCount(new QueryWrapper<SysUser>().eq("email", email)));
        return count != null && count > 0;
    }

    /**
     * 邮箱归一：大小写不同的同一邮箱是同一个人，唯一键、验证码存储键与占用判定必须用同一份口径。
     *
     * <p>复用 {@link EmailVerificationService#normalize}，不在这里另写一遍——两处口径一旦分叉，
     * 用户会遇到"验证码明明填对了却说过期"这种查不出来的故障。</p>
     */
    private String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? EmailVerificationService.normalize(email) : null;
    }

    /** MySQL 唯一键冲突信息里带索引名，据此区分是用户名撞了还是邮箱撞了。 */
    private boolean isEmailConflict(DuplicateKeyException e) {
        String message = e.getMessage();
        return message != null && message.contains("uk_sys_user_email");
    }

    private BizException duplicateUsername() {
        return new BizException(ResultCode.RESOURCE_DUPLICATE, "用户名已存在");
    }

    private BizException duplicateEmail() {
        return new BizException(ResultCode.RESOURCE_DUPLICATE, "该邮箱已注册，请直接登录或找回密码");
    }
}
