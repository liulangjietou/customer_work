package com.richard.fyoung.customeradmin.auth.guard;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自助注册准入判定。
 *
 * <p>这些用例是对外开放实例上"注册接口会不会被当成免费入口打"的机器防线。
 * 全部用真实实现（进程内计数器与验证码存储），不注 mock——注了 mock 就只能验到
 * "调用发生了"，验不到"到底拦不拦"，而后者才是这段代码存在的理由。</p>
 */
class RegistrationGuardTest {

    private static final String IP = "203.0.113.9";
    private static final String STRONG_PASSWORD = "secret12";

    private RegistrationGuardProperties properties;
    private PublicDeploymentProperties publicDeployment;
    private CaptchaStore captchaStore;
    private CaptchaService captchaService;
    private AdminMailSender mailSender;
    private RegistrationGuard guard;

    @BeforeEach
    void setUp() {
        properties = new RegistrationGuardProperties();
        publicDeployment = new PublicDeploymentProperties();
        captchaStore = new InMemoryCaptchaStore();
        captchaService = new CaptchaService(captchaStore, properties.getCaptcha());
        // 默认不可发信：本类只验图形码这条分支，邮箱验证有单独的测试类
        mailSender = org.mockito.Mockito.mock(AdminMailSender.class);
        guard = new RegistrationGuard(properties, publicDeployment, captchaService,
            new EmailVerificationService(properties, new InMemoryEmailVerificationStore(),
                mailSender, new InMemoryWindowCounter()),
            new InMemoryWindowCounter());
    }

    @Test
    void admit_shouldPassOnInternalDeploymentWithoutCaptchaOrEmail() {
        assertDoesNotThrow(() -> guard.admit(IP, null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD));
    }

    @Test
    void emailCodeCooldown_shouldExposeTheServerConfigurationToTheLoginPage() {
        properties.getEmailVerification().setResendCooldownSeconds(37);

        assertEquals(37, guard.emailCodeResendCooldownSeconds());
    }

    /**
     * 对外部署强制图形码、邮箱与邮箱验证码，且不看 {@code admin.registration.*} 的开关。
     *
     * <p>把公网实例的这几项配成关，等于把注册接口变成匿名可打的免费入口，
     * 这不该是一个能配错的选项。</p>
     */
    @Test
    void admit_shouldEnforceGuardsOnPublicDeploymentIgnoringConfig() {
        publicDeployment.setEnabled(true);
        properties.setCaptchaEnabled(false);
        properties.setEmailRequired(false);
        properties.getEmailVerification().setEnabled(false);

        assertTrue(guard.captchaRequired());
        assertTrue(guard.emailRequired());
        assertTrue(guard.emailVerificationRequired());

        // 开了邮箱验证后，注册这一步校验的是邮箱验证码而非图形码
        BizException noEmailCode = assertThrows(BizException.class,
            () -> guard.admit(IP, null, null, "a@example.com", null, STRONG_PASSWORD, STRONG_PASSWORD));
        assertEquals(ResultCode.EMAIL_CODE_INVALID, noEmailCode.getResultCode());
    }

    @Test
    void admit_shouldRejectMissingEmailOnPublicDeployment() {
        publicDeployment.setEnabled(true);

        BizException error = assertThrows(BizException.class, () -> guard.admit(
            IP, null, null, null, "123456", STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.PARAM_MISSING, error.getResultCode());
    }

    @Test
    void admit_shouldRejectWeakPassword() {
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, null, null, null, null, "abcdefgh", "abcdefgh"));

        assertEquals(ResultCode.PASSWORD_TOO_WEAK, error.getResultCode());
    }

    @Test
    void admit_shouldRejectPasswordConfirmationMismatch() {
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, null, null, null, null, STRONG_PASSWORD, "secret13"));

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
                () -> guard.admit(IP, null, null, null, null, "weak", "weak"));
        }

        // 额度一次未扣：仍能用完整的默认次数正常提交
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            assertDoesNotThrow(() -> guard.admit(IP, null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD));
        }
    }

    /** 限流窗口内第 6 次必须被拒；默认 5 次/小时。 */
    @Test
    void admit_shouldRejectAfterExceedingPerIpRateLimit() {
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            assertDoesNotThrow(() -> guard.admit(IP, null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD));
        }

        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.REGISTER_TOO_FREQUENT, error.getResultCode());
    }

    /** 限流按 IP 分桶：一个 IP 被限不该殃及其它来源。 */
    @Test
    void admit_shouldIsolateRateLimitPerSourceAddress() {
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            guard.admit(IP, null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD);
        }

        assertDoesNotThrow(() -> guard.admit("198.51.100.4", null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD));
    }

    /**
     * 限流必须排在验证码校验之前。
     *
     * <p>反过来的话，每一次攻击尝试都会先让服务端画一张验证码图——防滥用措施自己成了负载。
     * 这里用"超限时验证码仍然可用"来证明校验没有发生：被拒后那张验证码没有被消费掉。</p>
     */
    @Test
    void admit_shouldCheckRateLimitBeforeConsumingCaptcha() {
        properties.setCaptchaEnabled(true);
        CaptchaChallenge challenge = guard.issueCaptcha(IP);
        for (int i = 0; i < properties.getRateLimit().getMaxAttempts(); i++) {
            assertThrows(BizException.class,
                () -> guard.admit(IP, "not-exist", "0000", "a@example.com", null, STRONG_PASSWORD, STRONG_PASSWORD));
        }

        BizException error = assertThrows(BizException.class, () -> guard.admit(
            IP, challenge.captchaId(), answerOf(challenge), "a@example.com", null, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.REGISTER_TOO_FREQUENT, error.getResultCode());
        // 没被消费掉才能再次通过校验
        assertTrue(captchaService.verify(challenge.captchaId(), answerOf(challenge)));
    }

    @Test
    void admit_shouldRejectWhenSelfServiceDisabled() {
        properties.setSelfServiceEnabled(false);

        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.FEATURE_NOT_AVAILABLE, error.getResultCode());
    }

    /** 限流次数配成 0 或负数视为不限，用于内网关闭该能力。 */
    @Test
    void admit_shouldSkipRateLimitWhenMaxAttemptsNotPositive() {
        properties.getRateLimit().setMaxAttempts(0);

        for (int i = 0; i < 20; i++) {
            assertDoesNotThrow(() -> guard.admit(IP, null, null, null, null, STRONG_PASSWORD, STRONG_PASSWORD));
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

        CaptchaChallenge challenge = guard.issueCaptcha("198.51.100.23");
        // 注册额度未被验证码签发消耗，这一次仍应走到验证码校验而不是被限流拦下
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, challenge.captchaId(), "wrong", "a@example.com", null, STRONG_PASSWORD, STRONG_PASSWORD));
        assertEquals(ResultCode.CAPTCHA_INVALID, error.getResultCode());
    }

    /**
     * 读出刚签发的验证码答案。
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
