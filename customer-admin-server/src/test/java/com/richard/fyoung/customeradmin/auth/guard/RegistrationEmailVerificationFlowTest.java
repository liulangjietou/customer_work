package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationStore;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开启邮箱验证后的注册链路：发码 → 收码 → 注册。
 *
 * <p>钉住的核心是<b>两道验证码各自的位置</b>：图形码在「发码」那一步（挡住无人值守的脚本，
 * 因为发信是唯一会向站外第三方产生副作用的匿名操作），邮箱验证码在「注册」那一步
 * （证明申请人确实控制着那个邮箱）。两道都要、且都要在正确的位置，
 * 换成"注册时同时要图形码和邮箱码"只是多一个输入框，换不到任何额外保证。</p>
 */
class RegistrationEmailVerificationFlowTest {

    private static final String EMAIL = "richard@example.com";
    private static final String IP = "203.0.113.41";
    private static final String STRONG_PASSWORD = "secret12";

    private RegistrationGuardProperties properties;
    private PublicDeploymentProperties publicDeployment;
    private CaptchaStore captchaStore;
    private CaptchaService captchaService;
    private EmailVerificationStore emailStore;
    private AdminMailSender mailSender;
    private RegistrationGuard guard;

    @BeforeEach
    void setUp() {
        properties = new RegistrationGuardProperties();
        properties.getEmailVerification().setEnabled(true);
        publicDeployment = new PublicDeploymentProperties();
        captchaStore = new InMemoryCaptchaStore();
        captchaService = new CaptchaService(captchaStore, properties.getCaptcha());
        emailStore = new InMemoryEmailVerificationStore();
        mailSender = mock(AdminMailSender.class);
        when(mailSender.available()).thenReturn(true);
        when(mailSender.platformName()).thenReturn("客服智能体平台");
        guard = new RegistrationGuard(properties, publicDeployment, captchaService,
            new EmailVerificationService(properties, emailStore, mailSender, new InMemoryWindowCounter()),
            new InMemoryWindowCounter());
    }

    @Test
    void flow_shouldAcceptRegistrationAfterEmailCodeVerified() {
        int ttl = guard.sendEmailCode(EMAIL, null, null, IP);
        assertEquals(properties.getEmailVerification().getTtlSeconds(), ttl);
        String code = emailStore.get(EMAIL).code();

        assertDoesNotThrow(() -> guard.admit(IP, null, null, EMAIL, code,
            STRONG_PASSWORD, STRONG_PASSWORD));
    }

    /** 没有邮箱验证码就注册不了——这正是"填了别人的邮箱"这条路被堵死的地方。 */
    @Test
    void admit_shouldRejectWithoutEmailCode() {
        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, null, null, EMAIL, null, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.EMAIL_CODE_INVALID, error.getResultCode());
    }

    /** 一个邮箱收到的码不能拿去注册另一个邮箱。 */
    @Test
    void admit_shouldRejectCodeIssuedForAnotherAddress() {
        guard.sendEmailCode(EMAIL, null, null, IP);
        String code = emailStore.get(EMAIL).code();

        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, null, null, "someone-else@example.com", code,
                STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.EMAIL_CODE_INVALID, error.getResultCode());
    }

    /**
     * 图形码在发码那一步校验，不在注册那一步。
     *
     * <p>开启图形码后：不给图形码，发码被拒（信没发出去）；给对图形码拿到邮箱码后，
     * 注册那一步不再需要图形码。</p>
     */
    @Test
    void captcha_shouldGuardTheSendStepNotTheRegisterStep() {
        properties.setCaptchaEnabled(true);

        BizException noCaptcha = assertThrows(BizException.class,
            () -> guard.sendEmailCode(EMAIL, null, null, IP));
        assertEquals(ResultCode.CAPTCHA_INVALID, noCaptcha.getResultCode());
        verify(mailSender, never()).send(any(), any(), any());

        CaptchaChallenge challenge = guard.issueCaptcha(IP);
        guard.sendEmailCode(EMAIL, challenge.captchaId(), answerOf(challenge), IP);
        String code = emailStore.get(EMAIL).code();

        // 注册这一步不再要图形码——手里的邮箱码已经是更强的证据
        assertDoesNotThrow(() -> guard.admit(IP, null, null, EMAIL, code,
            STRONG_PASSWORD, STRONG_PASSWORD));
    }

    /** 关掉邮箱验证的内网实例保持原样：图形码仍在注册那一步。 */
    @Test
    void internalDeployment_shouldFallBackToCaptchaAtRegisterStep() {
        properties.getEmailVerification().setEnabled(false);
        properties.setCaptchaEnabled(true);

        BizException error = assertThrows(BizException.class,
            () -> guard.admit(IP, "not-exist", "0000", EMAIL, null, STRONG_PASSWORD, STRONG_PASSWORD));

        assertEquals(ResultCode.CAPTCHA_INVALID, error.getResultCode());
    }

    /** 未开启邮箱验证时不该有发码入口——那会让内网实例白白具备一个对外发信的匿名接口。 */
    @Test
    void sendEmailCode_shouldBeRejectedWhenEmailVerificationDisabled() {
        properties.getEmailVerification().setEnabled(false);

        BizException error = assertThrows(BizException.class,
            () -> guard.sendEmailCode(EMAIL, null, null, IP));

        assertEquals(ResultCode.FEATURE_NOT_AVAILABLE, error.getResultCode());
        verify(mailSender, never()).send(any(), any(), any());
    }

    @Test
    void sendEmailCode_shouldRejectWhenSelfServiceDisabled() {
        properties.setSelfServiceEnabled(false);

        BizException error = assertThrows(BizException.class,
            () -> guard.sendEmailCode(EMAIL, null, null, IP));

        assertEquals(ResultCode.FEATURE_NOT_AVAILABLE, error.getResultCode());
        verify(mailSender, never()).send(any(), any(), any());
    }

    @Test
    void sendEmailCode_shouldRejectBlankAddress() {
        BizException error = assertThrows(BizException.class,
            () -> guard.sendEmailCode("  ", null, null, IP));

        assertEquals(ResultCode.PARAM_MISSING, error.getResultCode());
    }

    /**
     * 对外部署强制开启邮箱验证与图形码，配置里关掉都不生效。
     *
     * <p>两道同时强制，且各在各的位置：发码要图形码（挡脚本），注册要邮箱码（证明邮箱归属）。
     * 这里走一遍完整链路，顺带钉住"对外部署下发码这一步不接受没有图形码"。</p>
     */
    @Test
    void publicDeployment_shouldForceBothGuardsAtTheirOwnStep() {
        properties.getEmailVerification().setEnabled(false);
        properties.setCaptchaEnabled(false);
        publicDeployment.setEnabled(true);

        assertTrue(guard.emailVerificationRequired());
        assertTrue(guard.captchaRequired());

        BizException noCaptcha = assertThrows(BizException.class,
            () -> guard.sendEmailCode(EMAIL, null, null, IP));
        assertEquals(ResultCode.CAPTCHA_INVALID, noCaptcha.getResultCode());

        CaptchaChallenge challenge = guard.issueCaptcha(IP);
        assertDoesNotThrow(() -> guard.sendEmailCode(EMAIL, challenge.captchaId(), answerOf(challenge), IP));
        String code = emailStore.get(EMAIL).code();
        assertDoesNotThrow(() -> guard.admit(IP, null, null, EMAIL, code,
            STRONG_PASSWORD, STRONG_PASSWORD));
    }

    /**
     * 开了邮箱验证就必然要求填邮箱。
     *
     * <p>两者独立判定的话，只开邮箱验证而忘了开 email-required 时，表单会放行一个空邮箱，
     * 然后在发码那一步才报"请填写注册邮箱"——把一个配置疏漏变成用户眼里的莫名其妙。</p>
     */
    @Test
    void emailVerification_shouldImplyEmailRequired() {
        properties.setEmailRequired(false);
        properties.getEmailVerification().setEnabled(true);

        assertTrue(guard.emailRequired());

        properties.getEmailVerification().setEnabled(false);
        assertTrue(!guard.emailRequired(), "两项都关时才允许不填邮箱");
    }

    /** 读出刚签发的图形验证码答案；读完写回去，不影响被测代码正常消费一次。 */
    private String answerOf(CaptchaChallenge challenge) {
        String answer = captchaStore.consume(challenge.captchaId());
        if (answer == null) {
            return "";
        }
        captchaStore.save(challenge.captchaId(), answer, properties.getCaptcha().getTtlSeconds());
        return answer.toUpperCase(Locale.ROOT);
    }
}
