package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.auth.email.EmailCodePurpose;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationCode;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.auth.email.InMemoryEmailVerificationStore;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.notify.AdminMailSender;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自助注册准入判定。
 *
 * <p>这些用例是"注册接口会不会被当成免费入口打"的机器防线。全部用真实实现
 * （进程内计数器与验证码存储），不注 mock——注了 mock 就只能验到"调用发生了"，
 * 验不到"到底拦不拦"，而后者才是这段代码存在的理由。</p>
 *
 * <p>邮箱与邮箱验证码是所有部署形态下的硬前提，所以每一条"应当放行"的用例都要先
 * 往存储里放一份有效验证码——这本身就是对"不填邮箱/不验证码进不来"的反复确认。</p>
 */
class RegistrationGuardTest {

    private static final String IP = "203.0.113.9";
    private static final String STRONG_PASSWORD = "secret12";
    private static final String EMAIL = "richard@example.com";
    private static final String EMAIL_CODE = "246810";
    private static final long TEN_MINUTES_MS = 600_000L;

    private RegistrationGuardProperties properties;
    private PublicDeploymentProperties publicDeployment;
    private CaptchaStore captchaStore;
    private CaptchaService captchaService;
    private InMemoryEmailVerificationStore emailCodeStore;
    private AdminMailSender mailSender;
    private RegistrationGuard guard;

    @BeforeEach
    void setUp() {
        properties = new RegistrationGuardProperties();
        publicDeployment = new PublicDeploymentProperties();
        captchaStore = new InMemoryCaptchaStore();
        captchaService = new CaptchaService(captchaStore, properties.getCaptcha());
        emailCodeStore = new InMemoryEmailVerificationStore();
        // 发信本身由 EmailVerificationServiceTest 覆盖；这里只关心准入判定，故直接往存储里放码
        mailSender = org.mockito.Mockito.mock(AdminMailSender.class);
        guard = new RegistrationGuard(properties, publicDeployment, captchaService,
            new EmailVerificationService(properties, emailCodeStore, mailSender, new InMemoryWindowCounter()),
            new InMemoryWindowCounter());
    }

    /**
     * 邮箱验证码没有关闭开关：内网默认配置与对外部署都拦。
     *
     * <p>断言的是行为而不是某个布尔方法的返回值——后者只能证明"有人写了 true"，
     * 证明不了那条判定真的挡在注册路径上。</p>
     */
    @Test
    void emailVerification_shouldBeRequiredOnEveryDeploymentShape() {
        BizException internal = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, null, STRONG_PASSWORD, STRONG_PASSWORD));
        assertEquals(ResultCode.EMAIL_CODE_INVALID, internal.getResultCode());

        publicDeployment.setEnabled(true);
        BizException external = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, null, STRONG_PASSWORD, STRONG_PASSWORD));
        assertEquals(ResultCode.EMAIL_CODE_INVALID, external.getResultCode());
    }

    @Test
    void admit_shouldPassWithMatchingEmailCode() {
        issueEmailCode();

        assertDoesNotThrow(() -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));
    }

    /** 通过即销毁：同一份码不能注册两个账号。 */
    @Test
    void admit_shouldConsumeEmailCodeOnSuccess() {
        issueEmailCode();
        guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD);

        BizException reuse = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, reuse.getResultCode());
    }

    /** 内网实例同样拦：这条规则不看任何配置，也不看是不是对外部署。 */
    @Test
    void admit_shouldRejectMissingEmailOnInternalDeployment() {
        BizException error = assertThrows(BizException.class, () -> guard.admit(
            IP, null, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.PARAM_MISSING, error.getResultCode());
    }

    @Test
    void admit_shouldRejectMissingEmailCode() {
        issueEmailCode();

        BizException error = assertThrows(BizException.class, () -> guard.admit(
            IP, EMAIL, null, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.EMAIL_CODE_INVALID, error.getResultCode());
    }

    /** 填了别人邮箱、手里没有那封信的人进不来——这正是邮箱验证要挡的那种人。 */
    @Test
    void admit_shouldRejectEmailWithoutIssuedCode() {
        BizException error = assertThrows(BizException.class, () -> guard.admit(
            IP, "someone-else@example.com", EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, error.getResultCode());
    }

    @Test
    void emailCodeCooldown_shouldExposeTheServerConfigurationToTheLoginPage() {
        properties.getEmailVerification().setResendCooldownSeconds(37);

        assertEquals(37, guard.emailCodeResendCooldownSeconds());
    }

    /**
     * 对外部署强制图形码，且不看 {@code admin.registration.captcha-enabled} 的开关。
     *
     * <p>把公网实例的验证码配成关，等于把发码接口变成匿名可打的免费入口，
     * 这不该是一个能配错的选项。注册那一步仍只认邮箱验证码。</p>
     */
    @Test
    void admit_shouldEnforceCaptchaOnPublicDeploymentIgnoringConfig() {
        publicDeployment.setEnabled(true);
        properties.setCaptchaEnabled(false);

        assertTrue(guard.captchaRequired());

        BizException noEmailCode = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, null, STRONG_PASSWORD, STRONG_PASSWORD));
        assertEquals(ResultCode.EMAIL_CODE_INVALID, noEmailCode.getResultCode());
    }

    @Test
    void admit_shouldRejectWeakPassword() {
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, EMAIL_CODE, "abcdefgh", "abcdefgh"));

        assertEquals(ResultCode.PASSWORD_TOO_WEAK, error.getResultCode());
    }

    @Test
    void admit_shouldRejectPasswordConfirmationMismatch() {
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, "secret13"));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
    }

    /**
     * 表单填错不该扣注册额度。
     *
     * <p>默认 5 次/小时，把密码强度这类纯参数校验排在限流之后的话，真人试错几回就被自己的
     * 防线锁在门外——而攻击者用一组合法参数就能跳过这一段，那个顺序只惩罚真人。</p>
     */
    @Test
    void admit_shouldNotConsumeRateLimitBudgetOnFormValidationFailure() {
        for (int i = 0; i < 20; i++) {
            assertThrows(BizException.class,
                () -> guard.admit(IP, EMAIL, EMAIL_CODE, "weak", "weak"));
        }

        // 额度一次未扣：仍能用完整的默认次数正常提交
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            issueEmailCode();
            assertDoesNotThrow(() -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));
        }
    }

    /** 限流窗口内第 6 次必须被拒；默认 5 次/小时。 */
    @Test
    void admit_shouldRejectAfterExceedingPerIpRateLimit() {
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            issueEmailCode();
            assertDoesNotThrow(() -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));
        }

        issueEmailCode();
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.REGISTER_TOO_FREQUENT, error.getResultCode());
    }

    /** 限流按 IP 分桶：一个 IP 被限不该殃及其它来源。 */
    @Test
    void admit_shouldIsolateRateLimitPerSourceAddress() {
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            issueEmailCode();
            guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD);
        }

        issueEmailCode();
        assertDoesNotThrow(() -> guard.admit("198.51.100.4", EMAIL, EMAIL_CODE,
            STRONG_PASSWORD, STRONG_PASSWORD));
    }

    /**
     * 限流必须排在邮箱验证码核验之前。
     *
     * <p>反过来的话，每一次攻击尝试都要写一次失败计数；更要紧的是失败次数有上限
     * （默认 5 次），密集试码能把受害者手里那份还有效的验证码提前耗到作废。
     * 这里用"超限后那份码仍然可用"来证明核验没有发生。</p>
     */
    @Test
    void admit_shouldCheckRateLimitBeforeVerifyingEmailCode() {
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            issueEmailCode();
            guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD);
        }
        issueEmailCode();

        BizException error = assertThrows(BizException.class, () -> guard.admit(
            IP, EMAIL, "000000", STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.REGISTER_TOO_FREQUENT, error.getResultCode());
        // 核验没跑：失败次数未累加，那份码原样还在
        EmailVerificationCode stored = emailCodeStore.get(EmailCodePurpose.REGISTER, EMAIL);
        assertNotNull(stored);
        assertEquals(0, stored.attempts());
    }

    @Test
    void admit_shouldRejectWhenSelfServiceDisabled() {
        properties.setSelfServiceEnabled(false);
        issueEmailCode();

        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.FEATURE_NOT_AVAILABLE, error.getResultCode());
    }

    /** 限流次数配成 0 或负数视为不限，用于内网关闭该能力。 */
    @Test
    void admit_shouldSkipRateLimitWhenMaxAttemptsNotPositive() {
        properties.getRateLimit().setMaxAttempts(0);

        for (int i = 0; i < 20; i++) {
            issueEmailCode();
            assertDoesNotThrow(() -> guard.admit(IP, EMAIL, EMAIL_CODE, STRONG_PASSWORD, STRONG_PASSWORD));
        }
    }

    @Test
    void captchaRequired_shouldFollowConfigOnInternalDeployment() {
        assertFalse(guard.captchaRequired());
        properties.setCaptchaEnabled(true);
        assertTrue(guard.captchaRequired());
    }

    /**
     * 验证码签发本身也要限流。
     *
     * <p>这是个免登接口，每次调用都要画一张图并写一次 Redis。不限的话它自己就是一条
     * 廉价的资源消耗路径——攻击者根本不必尝试注册。</p>
     */
    @Test
    void issueCaptcha_shouldRejectAfterExceedingPerIpIssueLimit() {
        int maxIssue = properties.getCaptcha().getMaxIssuePerWindow();
        for (int i = 0; i < maxIssue; i++) {
            assertDoesNotThrow(() -> guard.issueCaptcha(IP));
        }

        BizException error = assertThrows(BizException.class, () -> guard.issueCaptcha(IP));

        assertEquals(ResultCode.REGISTER_TOO_FREQUENT, error.getResultCode());
        assertDoesNotThrow(() -> guard.issueCaptcha("198.51.100.22"));
    }

    /**
     * 签发额度与注册额度是两个桶。
     *
     * <p>共用一个的话，看不清点着换几张图就把注册次数用光了——而那正是验证码要服务的人。</p>
     */
    @Test
    void issueCaptcha_shouldNotConsumeRegistrationRateLimitBudget() {
        properties.setCaptchaEnabled(true);
        for (int i = 0; i < properties.getCaptcha().getMaxIssuePerWindow(); i++) {
            guard.issueCaptcha(IP);
        }

        issueEmailCode();
        // 注册额度未被验证码签发消耗，这一次仍应走到邮箱码校验而不是被限流拦下
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, EMAIL, "000000", STRONG_PASSWORD, STRONG_PASSWORD));
        assertEquals(ResultCode.EMAIL_CODE_INVALID, error.getResultCode());
    }

    /** 发码链路整体不可用时必须当场失败，而不是让用户去等一封永远不会到的信。 */
    @Test
    void sendEmailCode_shouldFailFastWhenMailIsUnavailable() {
        org.mockito.Mockito.when(mailSender.available()).thenReturn(false);

        BizException error = assertThrows(BizException.class,
            () -> guard.sendEmailCode(EMAIL, null, null, IP));

        assertEquals(ResultCode.EMAIL_CODE_SEND_FAILED, error.getResultCode());
    }

    @Test
    void sendEmailCode_shouldRejectBlankEmail() {
        BizException error = assertThrows(BizException.class,
            () -> guard.sendEmailCode(" ", null, null, IP));

        assertEquals(ResultCode.PARAM_MISSING, error.getResultCode());
    }

    /** 发码那一步才是图形码的用武之地：它是唯一会向站外第三方产生副作用的匿名操作。 */
    @Test
    void sendEmailCode_shouldRejectWrongCaptcha() {
        properties.setCaptchaEnabled(true);
        guard.issueCaptcha(IP);

        BizException error = assertThrows(BizException.class,
            () -> guard.sendEmailCode(EMAIL, "not-exist", "0000", IP));

        assertEquals(ResultCode.CAPTCHA_INVALID, error.getResultCode());
    }

    /**
     * 图形码通过后才真正发信，用户手里也才有那份比图形码更强的证据。
     *
     * <p>这条同时钉住"发码成功会把码写进存储"——注册那一步能不能过，全看这一步的产物。</p>
     */
    @Test
    void sendEmailCode_shouldIssueCodeAfterCaptchaPassed() {
        properties.setCaptchaEnabled(true);
        org.mockito.Mockito.when(mailSender.available()).thenReturn(true);
        org.mockito.Mockito.when(mailSender.platformName()).thenReturn("客服智能体平台");
        CaptchaChallenge challenge = guard.issueCaptcha(IP);

        int ttlSeconds = guard.sendEmailCode(EMAIL, challenge.captchaId(), answerOf(challenge), IP);

        assertEquals(properties.getEmailVerification().getTtlSeconds(), ttlSeconds);
        assertNotNull(emailCodeStore.get(EmailCodePurpose.REGISTER, EMAIL));
    }

    /** 往存储里放一份有效验证码，等价于"用户已经收到了那封信"。 */
    private void issueEmailCode() {
        emailCodeStore.save(EmailCodePurpose.REGISTER, EMAIL, new EmailVerificationCode(
            EMAIL_CODE, 0, System.currentTimeMillis() + TEN_MINUTES_MS));
    }

    /**
     * 读出刚签发的图形验证码答案。
     *
     * <p>答案只存在于 Store 里（图片是画给人看的），所以测试直接读 Store。
     * {@code consume} 会删除，这里立刻写回去，让被测代码仍能正常消费一次。
     * 顺带覆盖了"校验大小写不敏感"——存的是小写，这里回传大写。</p>
     */
    private String answerOf(CaptchaChallenge challenge) {
        String answer = captchaStore.consume(challenge.captchaId());
        if (answer == null) {
            return "";
        }
        captchaStore.save(challenge.captchaId(), answer, properties.getCaptcha().getTtlSeconds());
        return answer.toUpperCase(Locale.ROOT);
    }
}
