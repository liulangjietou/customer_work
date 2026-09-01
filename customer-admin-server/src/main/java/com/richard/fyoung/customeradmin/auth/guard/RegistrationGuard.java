package com.richard.fyoung.customeradmin.auth.guard;

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
 * <p><b>邮箱验证码是不变式，不是选项</b>：{@link #admit} 无条件核验它，没有任何开关能关掉——
 * 任何部署形态下，注册都必须填邮箱并填回收到的验证码。"光填了邮箱"不等于"这个邮箱是他的"，
 * 而审核结果、密码重置都会发到那个地址；不验证的话，注册者随手填一个别人的邮箱，
 * 系统不会有任何异常表现，直到那封信真的寄到陌生人手里。</p>
 *
 * <p><b>图形验证码在对外部署下同样不看自身配置</b>：{@link #captchaRequired()} 在
 * {@code admin.public-deployment.enabled=true} 时恒为真，不接受被
 * {@code admin.registration.captcha-enabled} 关掉。把公网实例的验证码配成关，
 * 等于把发码接口变成匿名可打的免费入口，这不该是一个能配错的选项。</p>
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
     *   <li><b>有副作用的防滥用判定后做</b>：先 IP 限流，再核验邮箱验证码。这个先后不能反——
     *       核验失败要写一次失败计数，先校验意味着每一次攻击尝试都落一次存储；更要紧的是
     *       失败次数有上限，密集试码能把受害者手里那份还有效的验证码提前耗到作废。</li>
     * </ol>
     *
     * @param clientIp        来源 IP，用于频率统计
     * @param email           注册邮箱（调用方已归一为小写），必填
     * @param emailCode       邮箱验证码，必填
     * @param password        明文密码，仅用于强度判定，不做任何记录
     * @param confirmPassword 确认密码，与上一项比对
     */
    public void admit(String clientIp, String email, String emailCode,
                      String password, String confirmPassword) {
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
        if (!StringUtils.hasText(email)) {
            throw new BizException(ResultCode.PARAM_MISSING, "请填写注册邮箱");
        }
        // ---- 第二段：有副作用的防滥用判定 ----
        checkRateLimit(clientIp);
        // 注册这一步只认邮箱验证码：图形码已经在发码那一步挡过一次脚本，而手里这份邮箱码
        // 进一步证明申请人确实控制着那个邮箱——比图形码强得多，再要一次只是多一个输入框。
        emailVerificationService.verify(email, emailCode);
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
        if (!StringUtils.hasText(email)) {
            throw new BizException(ResultCode.PARAM_MISSING, "请填写注册邮箱");
        }
        if (captchaRequired() && !captchaService.verify(captchaId, captcha)) {
            throw new BizException(ResultCode.CAPTCHA_INVALID);
        }
        return emailVerificationService.sendCode(email, clientIp);
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
