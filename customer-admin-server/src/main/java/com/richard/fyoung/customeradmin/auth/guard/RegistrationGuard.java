package com.richard.fyoung.customeradmin.auth.guard;

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
    private final WindowCounter counter;

    public RegistrationGuard(RegistrationGuardProperties properties,
                             PublicDeploymentProperties publicDeployment,
                             CaptchaService captchaService,
                             WindowCounter counter) {
        this.properties = properties;
        this.publicDeployment = publicDeployment;
        this.captchaService = captchaService;
        this.counter = counter;
    }

    /** 对外实例强制要求验证码，内网实例按配置。 */
    public boolean captchaRequired() {
        return publicDeployment.isEnabled() || properties.isCaptchaEnabled();
    }

    /** 对外实例强制要求邮箱：没有联系方式就无法通知审核结果，也无法找回密码。 */
    public boolean emailRequired() {
        return publicDeployment.isEnabled() || properties.isEmailRequired();
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
     * @param captchaId       验证码凭据（未开启验证码时忽略）
     * @param captcha         用户输入的验证码
     * @param email           注册邮箱
     * @param password        明文密码，仅用于强度判定，不做任何记录
     * @param confirmPassword 确认密码，与上一项比对
     */
    public void admit(String clientIp, String captchaId, String captcha, String email,
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
        if (emailRequired() && !StringUtils.hasText(email)) {
            throw new BizException(ResultCode.PARAM_MISSING, "请填写注册邮箱");
        }
        // ---- 第二段：有副作用的防滥用判定 ----
        checkRateLimit(clientIp);
        if (captchaRequired() && !captchaService.verify(captchaId, captcha)) {
            throw new BizException(ResultCode.CAPTCHA_INVALID);
        }
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
