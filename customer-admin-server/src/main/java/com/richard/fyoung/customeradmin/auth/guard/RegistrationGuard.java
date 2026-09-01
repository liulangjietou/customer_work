package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.auth.email.EmailCodePurpose;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 自助注册的准入判定唯一入口。
 *
 * <p><b>为什么收敛成一个类</b>：注册这条链路上要判的东西有五项（开关、验证码、IP 频率、
 * 邮箱、密码强度），散在 Controller、DTO 校验注解和 Service 里各判一部分，
 * 就会出现"某一条路径漏了其中一项"——这正是本项目反复踩过的形状。
 * 现在 {@code UserRegistrationService} 只调 {@link #admit} 一次，新增判定只改这里。</p>
 *
 * <p><b>对外部署的强制项不看自身配置</b>：{@link #captchaRequired()} 与
 * {@link #emailRequired()} 在 {@code admin.public-deployment.enabled=true} 时恒为真，
 * 不接受被 {@code admin.registration.*} 关掉。把公网实例的验证码配成关，
 * 等于把注册接口变成匿名可打的免费入口，这不该是一个能配错的选项。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class RegistrationGuard {

    /** 计数键前缀，与 WindowCounter 里其它用途区分。 */
    private static final String RATE_LIMIT_KEY_PREFIX = "admin:register:ip:";

    /** 验证码签发单独计数：它比注册宽松，共用一个桶会让刷新几次图就把注册额度用光。 */
    private static final String CAPTCHA_ISSUE_KEY_PREFIX = "admin:captcha:ip:";

    private final RegistrationGuardProperties properties;
    private final PublicDeploymentProperties publicDeployment;
    private final CaptchaService captchaService;
    private final EmailVerificationService emailVerificationService;
    private final WindowCounter counter;

    public RegistrationGuard(RegistrationGuardProperties properties,
                             PublicDeploymentProperties publicDeployment,
                             CaptchaService captchaService,
                             EmailVerificationService emailVerificationService,
                             WindowCounter counter) {
        this.properties = properties;
        this.publicDeployment = publicDeployment;
        this.captchaService = captchaService;
        this.emailVerificationService = emailVerificationService;
        this.counter = counter;
    }

    /** 对外实例强制要求验证码，内网实例按配置。 */
    public boolean captchaRequired() {
        return publicDeployment.isEnabled() || properties.isCaptchaEnabled();
    }

    /**
     * 对外实例强制要求邮箱：没有联系方式就无法通知审核结果，也无法找回密码。
     *
     * <p><b>开了邮箱验证就必然要求填邮箱</b>——两者独立判定的话，内网实例只开
     * {@code email-verification.enabled} 而忘了开 {@code email-required} 时，
     * 表单会放行一个空邮箱，然后在发码那一步才报"请填写注册邮箱"，
     * 把一个配置疏漏变成用户眼里的莫名其妙。</p>
     */
    public boolean emailRequired() {
        return publicDeployment.isEnabled() || properties.isEmailRequired() || emailVerificationRequired();
    }

    /**
     * 是否要求邮箱验证码。对外实例强制开启。
     *
     * <p>光"填了邮箱"不等于"这个邮箱是他的"——不验证的话，注册者可以随手填一个别人的地址，
     * 而审核结果、密码重置都会发到那个人手里。收到验证码并填回来，是这件事的唯一证据。</p>
     */
    public boolean emailVerificationRequired() {
        return publicDeployment.isEnabled() || properties.getEmailVerification().isEnabled();
    }

    /** 前端发码按钮应与服务端使用同一重发冷却时间，避免配置变化后出现假倒计时。 */
    public int emailCodeResendCooldownSeconds() {
        return properties.getEmailVerification().getResendCooldownSeconds();
    }

    /** 自助注册总开关，关闭时只能由管理员预建账号。 */
    public boolean selfServiceEnabled() {
        return properties.isSelfServiceEnabled();
    }

    /**
     * 下发一次验证码挑战。
     *
     * <p>签发本身也要限流：这是个免登接口，每次调用都要画一张图并写一次 Redis，
     * 不限的话它自己就是一条廉价的资源消耗路径。额度比注册宽松——
     * 真人看不清会点着换几张。</p>
     */
    public CaptchaChallenge issueCaptcha(String clientIp) {
        int maxIssue = properties.getCaptcha().getMaxIssuePerWindow();
        if (maxIssue > 0 && !counter.tryAcquireSliding(CAPTCHA_ISSUE_KEY_PREFIX + clientIp,
            maxIssue, properties.getRateLimit().getWindowSeconds())) {
            log.info("captcha issue rejected by ip rate limit, ip={}, maxIssue={}", clientIp, maxIssue);
            throw new BizException(ResultCode.REGISTER_TOO_FREQUENT);
        }
        return captchaService.issue();
    }

    /**
     * 注册准入的完整判定。
     *
     * <p><b>顺序有讲究，分成两段</b>：</p>
     * <ol>
     *   <li><b>无副作用的表单校验先做</b>（密码一致、密码强度、邮箱必填）。它们只读参数、
     *       不碰任何计数器，把它们排在后面会让真人填错一次就白扣一次注册额度——
     *       默认 5 次/小时，试错几回就被自己的防线锁在门外。攻击者可以用合法参数跳过这一段，
     *       所以放前面不削弱防护，只让真人不受罚。</li>
     *   <li><b>有副作用的防滥用判定后做</b>：先 IP 限流，再验证码。这个先后不能反——
     *       验证码是一次性的，先校验意味着每一次攻击尝试都要让服务端画一张图并写一次 Redis，
     *       防滥用措施自己成了负载。</li>
     * </ol>
     *
     * @param clientIp        来源 IP，用于频率统计
     * @param captchaId       图形验证码凭据（仅在未开启邮箱验证时使用）
     * @param captcha         用户输入的图形验证码
     * @param email           注册邮箱（调用方已归一为小写）
     * @param emailCode       邮箱验证码
     * @param password        明文密码，仅用于强度判定，不做任何记录
     * @param confirmPassword 确认密码，与上一项比对
     */
    public void admit(String clientIp, String captchaId, String captcha, String email,
                      String emailCode, String password, String confirmPassword) {
        if (!selfServiceEnabled()) {
            throw new BizException(ResultCode.FEATURE_NOT_AVAILABLE, "本系统未开放自助注册，请联系管理员开通账号");
        }
        // ---- 第一段：无副作用的表单校验 ----
        if (!Objects.equals(password, confirmPassword)) {
            throw new BizException(ResultCode.PARAM_INVALID, "两次输入的密码不一致");
        }
        if (!PasswordPolicy.isStrongEnough(password)) {
            throw new BizException(ResultCode.PASSWORD_TOO_WEAK);
        }
        if (emailRequired() && !StringUtils.hasText(email)) {
            throw new BizException(ResultCode.PARAM_MISSING, "请填写注册邮箱");
        }
        // ---- 第二段：有副作用的防滥用判定 ----
        checkRateLimit(clientIp);
        verifyHumanEvidence(captchaId, captcha, email, emailCode);
    }

    /**
     * "这不是脚本"的证据，二选一而不是两个都要。
     *
     * <p>开了邮箱验证时，图形码已经在<b>发码那一步</b>挡过一次脚本，而手里这份邮箱验证码
     * 进一步证明了申请人确实控制着那个邮箱——比图形码强得多。此时再要一次图形码是纯粹的
     * 体验损耗：多一个输入框，换不到任何额外保证。</p>
     *
     * <p>没开邮箱验证时（内网实例），图形码就是唯一的那道，仍在注册这一步校验。</p>
     */
    private void verifyHumanEvidence(String captchaId, String captcha, String email, String emailCode) {
        if (emailVerificationRequired()) {
            emailVerificationService.verify(EmailCodePurpose.REGISTER, email, emailCode);
            return;
        }
        if (captchaRequired() && !captchaService.verify(captchaId, captcha)) {
            throw new BizException(ResultCode.CAPTCHA_INVALID);
        }
    }

    /**
     * 发送注册邮箱验证码。
     *
     * <p>图形验证码在这里校验而不是在注册那一步——发信是唯一会向站外第三方产生副作用的
     * 匿名操作（服务端替调用者给任意地址发一封信），它才是最该先挡住脚本的地方。</p>
     *
     * @return 验证码有效期（秒）
     */
    public int sendEmailCode(String email, String captchaId, String captcha, String clientIp) {
        if (!selfServiceEnabled()) {
            throw new BizException(ResultCode.FEATURE_NOT_AVAILABLE, "本系统未开放自助注册，请联系管理员开通账号");
        }
        if (!emailVerificationRequired()) {
            throw new BizException(ResultCode.FEATURE_NOT_AVAILABLE, "本实例未开启邮箱验证");
        }
        if (!StringUtils.hasText(email)) {
            throw new BizException(ResultCode.PARAM_MISSING, "请填写注册邮箱");
        }
        if (captchaRequired() && !captchaService.verify(captchaId, captcha)) {
            throw new BizException(ResultCode.CAPTCHA_INVALID);
        }
        return emailVerificationService.sendCode(EmailCodePurpose.REGISTER, email, clientIp);
    }

    /**
     * 按来源 IP 的滑动窗口限流。
     *
     * <p>用 {@code tryAcquireSliding} 而不是"先加再判"：超限时不记录本次，
     * 否则持续打压会让窗口一直往后推，正常用户在攻击停止后仍长时间进不来。</p>
     */
    private void checkRateLimit(String clientIp) {
        RegistrationGuardProperties.RateLimit rule = properties.getRateLimit();
        if (rule.getMaxAttempts() <= 0) {
            return;
        }
        boolean allowed = counter.tryAcquireSliding(RATE_LIMIT_KEY_PREFIX + clientIp,
            rule.getMaxAttempts(), rule.getWindowSeconds());
        if (!allowed) {
            log.info("registration rejected by ip rate limit, ip={}, maxAttempts={}, windowSeconds={}",
                clientIp, rule.getMaxAttempts(), rule.getWindowSeconds());
            throw new BizException(ResultCode.REGISTER_TOO_FREQUENT);
        }
    }
}
