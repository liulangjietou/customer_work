package com.richard.fyoung.customeradmin.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.auth.dto.PasswordResetEmailCodeRequest;
import com.richard.fyoung.customeradmin.auth.dto.PasswordResetRequest;
import com.richard.fyoung.customeradmin.auth.email.EmailCodePurpose;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.auth.guard.CaptchaService;
import com.richard.fyoung.customeradmin.auth.guard.LoginAttemptGuard;
import com.richard.fyoung.customeradmin.auth.guard.PasswordPolicy;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.notify.AdminMailSender;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 凭注册邮箱找回登录密码。
 *
 * <p><b>为什么走验证码而不是重置链接</b>：与注册验证码同一个理由——"重置密码链接"正是钓鱼邮件
 * 最常用的幌子，正文里出现可点的链接会把"别点邮件里的链接"这条常识教反。另有两个实际问题：
 * 链接需要一个可靠的对外基础 URL（多域名或反代下极易配错，配错就是把凭据发向错误的域名），
 * 而企业邮件安全网关会预取邮件里的链接，把一次性 token 静默消费掉。</p>
 *
 * <p><b>响应一律含糊，与注册接口刻意相反</b>：注册重名时明确提示"用户名已存在"是注册页的
 * 可用性底线；而这里无论邮箱没注册、用户名与邮箱对不上、还是账号根本不能重置，返回值都完全一样。
 * 差异会让这个匿名接口变成"某某是不是这里的用户、他的邮箱是不是这个"的查询服务。
 * 同一个理由贯穿三处：</p>
 * <ol>
 *   <li>发码时账号对不上也照样<b>先扣掉发信额度</b>——先查账号再扣的话，"有没有被限流"
 *       本身就成了存在性探针，含糊的文案白写；</li>
 *   <li>重置时账号对不上、验证码错、验证码过期，三者合并成
 *       {@link ResultCode#PASSWORD_RESET_REJECTED} 一个码；</li>
 *   <li>不能重置的账号（OA 域账号、已禁用）改发一封说明信，而不是在响应里说明——
 *       信只有邮箱的主人收得到，响应则是谁问谁得。</li>
 * </ol>
 *
 * <p><b>不设独立开关</b>：能力跟随 {@link AdminMailSender#available()}。多一个
 * {@code enabled} 配置项就多一个漏配点，而这个功能配错的后果是"用户永远找不回密码"，
 * 且没有任何人会收到告警。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class PasswordResetService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final CaptchaService captchaService;
    private final AdminMailSender mailSender;
    private final SessionRevocationService sessionRevocationService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final OperationLogMapper operationLogMapper;

    public PasswordResetService(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
                                EmailVerificationService emailVerificationService,
                                CaptchaService captchaService, AdminMailSender mailSender,
                                SessionRevocationService sessionRevocationService,
                                LoginAttemptGuard loginAttemptGuard,
                                OperationLogMapper operationLogMapper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.captchaService = captchaService;
        this.mailSender = mailSender;
        this.sessionRevocationService = sessionRevocationService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 本实例当前是否真的能找回密码。
     *
     * <p>问的是"能不能发信"而不是"配没配"：{@code MailNotificationConfig} 在 host 为空时
     * 根本不会创建 sender，此时登录页显示入口只会把人引进一条死路。</p>
     */
    public boolean available() {
        return mailSender.available();
    }

    /**
     * 向账号登记的邮箱发一封重置验证码。
     *
     * <p>顺序是这条链路的核心，不能调换：图形码 → 扣额度 → 查账号 → 发信。
     * 图形码在最前是因为发信是唯一会向站外第三方产生副作用的匿名操作，脚本必须挡在它前面；
     * 扣额度在查账号之前，是为了让"存在"与"不存在"两种请求在限流上不可区分。</p>
     *
     * @param request  用户名 + 邮箱 + 图形验证码
     * @param clientIp 来源 IP
     * @return 验证码有效期（秒）。<b>无论是否真的发出，返回值都相同</b>
     */
    public int sendCode(PasswordResetEmailCodeRequest request, String clientIp) {
        requireAvailable();
        // 图形码无条件校验，不看部署形态：注册那条链路在内网可以省掉它（注册要过审核、
        // 建出来的号也拿不到权限），而这里对着的是一个已经存在、多半已获授权的账号
        if (!captchaService.verify(request.captchaId(), request.captcha())) {
            throw new BizException(ResultCode.CAPTCHA_INVALID);
        }
        String email = EmailVerificationService.normalize(request.email());
        String username = request.username().trim();

        emailVerificationService.reserveSendQuota(email, clientIp);

        SysUser user = findByUsername(username);
        if (user == null || !matchesEmail(user, email)) {
            // 不记 username/email：这行日志正是攻击者拿不到、而运维也不需要的那部分信息
            log.info("password reset code requested for an account that does not match, ip={}", clientIp);
            return emailVerificationService.codeTtlSeconds();
        }
        if (!SysUser.LOGIN_TYPE_LOCAL.equals(user.getLoginType())) {
            notifyUnresettable(user, "该账号是 OA 域账号，登录密码由企业域控统一管理，本平台既不保存也无法重置。"
                + "请使用 OA 账号登录入口，或联系企业 IT 重置域账号密码。");
            return emailVerificationService.codeTtlSeconds();
        }
        if (!enabled(user)) {
            notifyUnresettable(user, "该账号当前已被禁用，重置密码后仍然无法登录，因此本次未发送验证码。"
                + "请联系管理员恢复账号后再试。");
            return emailVerificationService.codeTtlSeconds();
        }
        emailVerificationService.issueAndSend(EmailCodePurpose.PASSWORD_RESET, email);
        log.info("password reset code sent, userId={}", user.getId());
        return emailVerificationService.codeTtlSeconds();
    }

    /**
     * 核验验证码并改密。
     *
     * <p>收尾与 {@code AuthService#changePassword} 逐条一致，一件都不能省：递增认证版本、
     * 撤销既有会话、清零登录失败计数，另加一条操作日志留痕。<b>只改 password 列不撤会话</b>，
     * 意味着已经登进去的那个人照旧待在里面——而"我怀疑号被盗了"恰恰是重置密码最常见的动机。</p>
     *
     * <p>顺带把 {@code email_verified} 置 1：能收到发往该地址的验证码并填回来，
     * 本身就是一次不折不扣的邮箱所有权证明，比注册时那次验证只强不弱。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void reset(PasswordResetRequest request, String clientIp) {
        // 这一步刻意不要求邮件可用：核验与改密都不发信，而 SMTP 抖动不该把已经拿到码的人挡在门外。
        // 邮件长期不可用的实例根本没人能拿到码，此处的每个请求都会止于验证码核验。

        // 无副作用的表单校验先做：与 RegistrationGuard#admit 的分段理由一致，
        // 真人把两次密码敲得不一致，不该白白消耗掉手里那份验证码的重试次数
        if (!Objects.equals(request.newPassword(), request.confirmPassword())) {
            throw new BizException(ResultCode.PARAM_INVALID, "两次输入的密码不一致");
        }
        if (!PasswordPolicy.isStrongEnough(request.newPassword())) {
            throw new BizException(ResultCode.PASSWORD_TOO_WEAK);
        }

        String email = EmailVerificationService.normalize(request.email());
        String username = request.username().trim();

        // 账号匹配判定要排在核验之前：否则任何人拿一个错的用户名反复提交，
        // 就能把受害者手里那份真验证码的重试次数耗光，等于替他把码作废
        SysUser user = findByUsername(username);
        if (user == null || !matchesEmail(user, email)
            || !SysUser.LOGIN_TYPE_LOCAL.equals(user.getLoginType()) || !enabled(user)) {
            log.info("password reset rejected, account does not match or is not resettable, ip={}", clientIp);
            throw new BizException(ResultCode.PASSWORD_RESET_REJECTED);
        }
        verifyCode(email, request.emailCode(), clientIp);

        applyNewPassword(user, request.newPassword());
        recordResetLog(user, clientIp);
        sessionRevocationService.revokeUserAfterCommit(user.getId());
        // 真实用户往往正是被自己输错的那几次锁在门外，才想起来重置密码；
        // 不清的话他还得干等锁定窗口过去，这个功能只解决了一半问题
        loginAttemptGuard.clearFailures(username, clientIp);
        log.info("password reset completed, userId={}", user.getId());
    }

    /**
     * 核验重置验证码，把"码错"与"码已失效"两种结果合并成同一个响应。
     *
     * <p>分开报会让攻击者能用一个随机验证码试出"这个用户名和这个邮箱是不是一对"——
     * 只有真发过码的组合才会得到"码错"。真实用户在这两种情况下的动作都是重新获取一次，
     * 合并不损失可操作性。原始区分留在日志里。</p>
     */
    private void verifyCode(String email, String emailCode, String clientIp) {
        try {
            emailVerificationService.verify(EmailCodePurpose.PASSWORD_RESET, email, emailCode);
        } catch (BizException e) {
            log.info("password reset code verification failed, reason={}, ip={}",
                e.getResultCode().name(), clientIp);
            throw new BizException(ResultCode.PASSWORD_RESET_REJECTED);
        }
    }

    /**
     * 写入新密码。
     *
     * <p>必须自带租户上下文：本方法跑在匿名请求里，而 {@code sys_user} 参与租户行过滤，
     * 没有上下文的持久层操作会 fail-closed 直接抛错。</p>
     *
     * <p>只更新三列而不回写整行：{@code updateById} 按非空字段拼 SET，把查出来的整个实体写回去
     * 会连带覆盖登录时间等与本次操作无关的列。</p>
     */
    private void applyNewPassword(SysUser user, String rawPassword) {
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(rawPassword));
        update.setEmailVerified(SysUser.EMAIL_VERIFIED);
        TenantContext.runWith(tenantOf(user), () -> {
            userMapper.updateById(update);
            if (userMapper.incrementAuthEpoch(user.getId()) != 1) {
                // 行在这中间被删了或被并发改动，回滚而不是留下一个改了密码却没撤权的中间态
                log.error("password reset failed to bump auth epoch, code={}, userId={}",
                    "AUTH-PASSWORD-RESET-EPOCH-FAIL", user.getId());
                throw new BizException(ResultCode.PASSWORD_RESET_REJECTED);
            }
        });
    }

    /**
     * 把成功的重置写进操作日志表。
     *
     * <p>"某账号的密码在什么时候被从哪个 IP 重置了"是要能事后查的安全事件，而应用日志翻不到、
     * 后台的操作日志页面才是运营查得到的地方。这里手工写而不用 {@code @OperationLog} 切面：
     * 那个切面靠 {@code StpUtil.isLogin()} 解析操作人，而这条链路自始至终是匿名的
     * （与 {@code AuthService#recordLoginLog} 同一个理由）。</p>
     *
     * <p><b>只记成功的</b>：失败的尝试全都被刻意做成不可区分的，把它们逐条写进日志表
     * 等于把"谁被试过"记下来，而这正是整条链路在向调用方隐藏的东西；失败的排查线索
     * 由应用日志的 info 承担。</p>
     *
     * <p>留痕失败不能连累已经完成的改密：密码已经改了，这时抛异常回滚只会造出
     * "用户以为失败、其实已改"的最坏状态。</p>
     */
    private void recordResetLog(SysUser user, String clientIp) {
        try {
            SysOperationLog entity = new SysOperationLog();
            entity.setUserId(user.getId());
            entity.setUsername(user.getUsername());
            entity.setOperation("重置密码");
            entity.setMethod("AuthController#resetPassword");
            entity.setTarget("sys_user");
            entity.setResult(SysOperationLog.RESULT_SUCCESS);
            entity.setIp(clientIp);
            entity.initializeAudit(SysOperationLog.AUDIT_COMPLETED, LocalDateTime.now());
            TenantContext.runWith(tenantOf(user), () -> operationLogMapper.insert(entity));
        } catch (Exception e) {
            log.error("record password reset log failed, code={}, userId={}",
                "AUTH-PASSWORD-RESET-LOG-FAIL", user.getId(), e);
        }
    }

    /**
     * 告诉邮箱主人"这个账号不能这样重置"。
     *
     * <p>为什么要发这封信：不发的话，OA 用户会一直等一封永远不会到的验证码，
     * 而我们又不能在响应里告诉他原因（那就成了账号类型探针）。信只有邮箱的主人收得到，
     * 说明原因不泄露给任何第三方。</p>
     *
     * <p>发送失败只记日志：这是旁路通知，与验证码那封相反——那封发不出去必须让用户当场看到失败。</p>
     */
    private void notifyUnresettable(SysUser user, String reason) {
        // 邮箱一定有值：走到这里意味着它刚与请求里的地址比对成功（matchesEmail）
        try {
            mailSender.send(user.getEmail(), "密码重置申请",
                "我们收到了针对账号 " + user.getUsername() + " 的密码重置申请。\n\n"
                    + reason + "\n\n若非本人操作，请忽略本邮件。");
            log.info("password reset unavailable notice sent, userId={}", user.getId());
        } catch (Exception e) {
            log.error("password reset notice send failed, code={}, userId={}",
                "AUTH-PASSWORD-RESET-NOTICE-FAIL", user.getId(), e);
        }
    }

    private void requireAvailable() {
        if (!available()) {
            log.error("password reset requested but mail is unavailable, code={}",
                "AUTH-PASSWORD-RESET-NO-MAIL");
            throw new BizException(ResultCode.FEATURE_NOT_AVAILABLE,
                "本系统未配置邮件服务，无法自助找回密码，请联系管理员");
        }
    }

    /**
     * 按用户名跨租户定位账号。
     *
     * <p>{@code sys_user.username} 全局唯一（见 docs/多租户架构设计.md §2.3），
     * 而此刻没有任何登录态、也就没有租户上下文，与登录链路的处理完全一致。</p>
     */
    private SysUser findByUsername(String username) {
        // 用字符串列名而不是 lambda wrapper：lambda 解析依赖 TableInfo 缓存，
        // 不启动容器的单测里拿不到，会直接抛 MybatisPlusException
        return CrossTenantOperations.execute(() -> userMapper.selectOne(
            new QueryWrapper<SysUser>().eq("username", username)));
    }

    /** 邮箱比对走同一份归一口径，大小写不同的同一地址必须算匹配。 */
    private boolean matchesEmail(SysUser user, String normalizedEmail) {
        String stored = EmailVerificationService.normalize(user.getEmail());
        return StringUtils.hasText(stored) && stored.equals(normalizedEmail);
    }

    private boolean enabled(SysUser user) {
        return user.getStatus() != null && user.getStatus() == 1;
    }

    /** 存量用户可能没有租户列值（升级前建的行），与登录链路一样归入 default。 */
    private String tenantOf(SysUser user) {
        String tenantId = user.getTenantId();
        return StringUtils.hasText(tenantId) ? tenantId : TenantContext.DEFAULT;
    }
}
