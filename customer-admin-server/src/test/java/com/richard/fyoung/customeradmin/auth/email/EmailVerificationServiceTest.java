package com.richard.fyoung.customeradmin.auth.email;

import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.notify.AdminMailSender;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册邮箱验证码。
 *
 * <p>发码是这套系统里<b>唯一会向站外第三方产生副作用的匿名操作</b>——服务端替调用者
 * 给任意地址发一封信。下面的用例逐条钉住那四道防线，以及"发不出去就别留死码"这类
 * 只在失败路径上才成立的性质。</p>
 */
class EmailVerificationServiceTest {

    private static final EmailCodePurpose PURPOSE = EmailCodePurpose.REGISTER;
    private static final String EMAIL = "richard@example.com";
    private static final String IP = "203.0.113.31";

    private RegistrationGuardProperties properties;
    private EmailVerificationStore store;
    private AdminMailSender mailSender;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        properties = new RegistrationGuardProperties();
        store = new InMemoryEmailVerificationStore();
        mailSender = mock(AdminMailSender.class);
        when(mailSender.available()).thenReturn(true);
        when(mailSender.platformName()).thenReturn("客服智能体平台");
        service = new EmailVerificationService(properties, store, mailSender, new InMemoryWindowCounter());
    }

    @Test
    void sendCode_shouldStoreCodeAndMailItToTheAddress() {
        int ttl = service.sendCode(PURPOSE, EMAIL, IP);

        assertEquals(properties.getEmailVerification().getTtlSeconds(), ttl);
        EmailVerificationCode stored = store.get(PURPOSE, EMAIL);
        assertNotNull(stored);
        assertEquals(properties.getEmailVerification().getCodeLength(), stored.code().length());
        assertTrue(stored.code().matches("\\d+"), "验证码应为纯数字，便于在手机上转录");

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(toCaptor.capture(), any(), textCaptor.capture());
        assertEquals(EMAIL, toCaptor.getValue());
        assertTrue(textCaptor.getValue().contains(stored.code()), "正文里必须带上那串码");
    }

    /**
     * 邮件正文刻意不带链接。
     *
     * <p>注册验证码邮件是钓鱼最爱模仿的形态，正文里出现可点的链接会把"别点邮件里的链接"
     * 这条常识教反。用户回到自己打开的那个页面填码即可。</p>
     */
    @Test
    void sendCode_shouldNotPutAnyClickableLinkInMailBody() {
        service.sendCode(PURPOSE, EMAIL, IP);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(any(), any(), textCaptor.capture());
        String body = textCaptor.getValue();
        assertTrue(!body.contains("http://") && !body.contains("https://"),
            "验证码邮件正文不应出现链接：" + body);
    }

    @Test
    void verify_shouldPassWithCorrectCodeAndConsumeIt() {
        service.sendCode(PURPOSE, EMAIL, IP);
        String code = store.get(PURPOSE, EMAIL).code();

        assertDoesNotThrow(() -> service.verify(PURPOSE, EMAIL, code));

        // 同一份码不能注册两个账号
        assertNull(store.get(PURPOSE, EMAIL));
        BizException replay = assertThrows(BizException.class, () -> service.verify(PURPOSE, EMAIL, code));
        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, replay.getResultCode());
    }

    /**
     * 输错不立刻作废，但有上限。
     *
     * <p>错一次就作废会逼着用户为一个笔误重新收信，而每一次重发都是一封真实的外部邮件；
     * 完全不限则 6 位数字可以直接猜穿。</p>
     */
    @Test
    void verify_shouldAllowRetriesUntilMaxAttemptsThenInvalidate() {
        service.sendCode(PURPOSE, EMAIL, IP);
        String code = store.get(PURPOSE, EMAIL).code();
        String wrongCode = wrongCodeFor(code);
        int maxAttempts = properties.getEmailVerification().getMaxAttempts();

        for (int i = 0; i < maxAttempts - 1; i++) {
            BizException retryable = assertThrows(BizException.class,
                () -> service.verify(PURPOSE, EMAIL, wrongCode));
            assertEquals(ResultCode.EMAIL_CODE_INVALID, retryable.getResultCode(),
                "未耗尽尝试次数时客户端应允许继续输入当前验证码");
            assertNotNull(store.get(PURPOSE, EMAIL), "未达上限前验证码应当仍然有效");
        }
        // 中途输对仍然算数
        assertDoesNotThrow(() -> service.verify(PURPOSE, EMAIL, code));
    }

    @Test
    void verify_shouldInvalidateCodeAfterMaxFailedAttempts() {
        service.sendCode(PURPOSE, EMAIL, IP);
        String code = store.get(PURPOSE, EMAIL).code();
        String wrongCode = wrongCodeFor(code);
        int maxAttempts = properties.getEmailVerification().getMaxAttempts();

        for (int i = 0; i < maxAttempts - 1; i++) {
            BizException retryable = assertThrows(BizException.class,
                () -> service.verify(PURPOSE, EMAIL, wrongCode));
            assertEquals(ResultCode.EMAIL_CODE_INVALID, retryable.getResultCode());
        }
        BizException exhausted = assertThrows(BizException.class,
            () -> service.verify(PURPOSE, EMAIL, wrongCode));

        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, exhausted.getResultCode(),
            "次数耗尽后客户端必须引导重新获取验证码");
        assertNull(store.get(PURPOSE, EMAIL), "失败次数耗尽后验证码必须作废");
        BizException error = assertThrows(BizException.class, () -> service.verify(PURPOSE, EMAIL, code));
        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, error.getResultCode());
    }

    @Test
    void verify_shouldRequireReissueWhenCodeDoesNotExist() {
        BizException error = assertThrows(BizException.class,
            () -> service.verify(PURPOSE, EMAIL, "123456"));

        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, error.getResultCode());
    }

    @Test
    void verify_shouldRequireReissueWhenCodeExpired() {
        store.save(PURPOSE, EMAIL, new EmailVerificationCode("123456", 0, System.currentTimeMillis() - 1));

        BizException error = assertThrows(BizException.class,
            () -> service.verify(PURPOSE, EMAIL, "123456"));

        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, error.getResultCode());
        assertNull(store.get(PURPOSE, EMAIL));
    }

    /** 失败重写不得续期，否则不断试错就能让验证码永不过期，正好方便暴力猜码。 */
    @Test
    void verify_shouldKeepOriginalExpiryWhenRecordingFailure() {
        service.sendCode(PURPOSE, EMAIL, IP);
        String code = store.get(PURPOSE, EMAIL).code();
        long expireAtBefore = store.get(PURPOSE, EMAIL).expireAtMs();

        BizException error = assertThrows(BizException.class,
            () -> service.verify(PURPOSE, EMAIL, wrongCodeFor(code)));

        assertEquals(ResultCode.EMAIL_CODE_INVALID, error.getResultCode());
        assertEquals(expireAtBefore, store.get(PURPOSE, EMAIL).expireAtMs());
    }

    @Test
    void verify_shouldRejectBlankInput() {
        service.sendCode(PURPOSE, EMAIL, IP);

        BizException error = assertThrows(BizException.class, () -> service.verify(PURPOSE, EMAIL, "  "));

        assertEquals(ResultCode.EMAIL_CODE_INVALID, error.getResultCode());
        assertNotNull(store.get(PURPOSE, EMAIL), "空输入不该消耗尝试次数之外的东西，码仍应有效");
    }

    /** 冷却挡的是对同一个受害者的高频轰炸。 */
    @Test
    void sendCode_shouldRejectResendWithinCooldown() {
        service.sendCode(PURPOSE, EMAIL, IP);

        BizException error = assertThrows(BizException.class, () -> service.sendCode(PURPOSE, EMAIL, IP));

        assertEquals(ResultCode.EMAIL_CODE_TOO_FREQUENT, error.getResultCode());
        verify(mailSender, times(1)).send(any(), any(), any());
    }

    /** 日总量挡的是"每 60 秒一封、发一整天"——冷却对这种打法完全无效。 */
    @Test
    void sendCode_shouldRejectAfterExceedingPerEmailDailyQuota() {
        properties.getEmailVerification().setResendCooldownSeconds(0);
        properties.getEmailVerification().setMaxSendPerEmailPerDay(3);

        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> service.sendCode(PURPOSE, EMAIL, IP));
        }

        BizException error = assertThrows(BizException.class, () -> service.sendCode(PURPOSE, EMAIL, IP));
        assertEquals(ResultCode.EMAIL_CODE_TOO_FREQUENT, error.getResultCode());
    }

    /** IP 限流挡的是一个来源换着邮箱轰炸——冷却与日限都是按邮箱计的，管不到这种打法。 */
    @Test
    void sendCode_shouldRejectAfterExceedingPerIpQuota() {
        properties.getEmailVerification().setResendCooldownSeconds(0);
        properties.getEmailVerification().setMaxSendPerIpPerWindow(3);

        for (int i = 0; i < 3; i++) {
            int index = i;
            assertDoesNotThrow(() -> service.sendCode(PURPOSE, "victim" + index + "@example.com", IP));
        }

        BizException error = assertThrows(BizException.class,
            () -> service.sendCode(PURPOSE, "victim9@example.com", IP));
        assertEquals(ResultCode.EMAIL_CODE_TOO_FREQUENT, error.getResultCode());
        // 换个来源仍然放行，证明限的是 IP 而不是全局
        assertDoesNotThrow(() -> service.sendCode(PURPOSE, "victim9@example.com", "198.51.100.44"));
    }

    /** 邮件服务没配好时当场失败，而不是让用户干等一封永远不会到的信。 */
    @Test
    void sendCode_shouldFailFastWhenMailIsUnavailable() {
        when(mailSender.available()).thenReturn(false);

        BizException error = assertThrows(BizException.class, () -> service.sendCode(PURPOSE, EMAIL, IP));

        assertEquals(ResultCode.EMAIL_CODE_SEND_FAILED, error.getResultCode());
        assertNull(store.get(PURPOSE, EMAIL));
        verify(mailSender, never()).send(any(), any(), any());
    }

    /**
     * 信没发出去就不能留下验证码。
     *
     * <p>留着的话它是一份死码：用户拿不到，而下一次重发会被冷却挡住——
     * 相当于把这个邮箱锁死一个冷却周期。</p>
     */
    @Test
    void sendCode_shouldNotLeaveDeadCodeWhenMailFails() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(), any(), any());

        BizException error = assertThrows(BizException.class, () -> service.sendCode(PURPOSE, EMAIL, IP));

        assertEquals(ResultCode.EMAIL_CODE_SEND_FAILED, error.getResultCode());
        assertNull(store.get(PURPOSE, EMAIL), "发送失败必须清掉刚写入的验证码");
    }

    /** 大小写不同的同一邮箱是同一个人，键与唯一约束都必须能挡住。 */
    @Test
    void normalize_shouldLowercaseAndTrim() {
        assertEquals("richard@example.com", EmailVerificationService.normalize("  Richard@Example.COM "));
        assertNull(EmailVerificationService.normalize(null));
    }

    /**
     * 验证码按用途分空间：一封注册码核验不了重置那一侧。
     *
     * <p>而且这次失败的尝试不该动到注册码本身——两条链路互不影响，
     * 也就没法拿一条去骚扰另一条。</p>
     */
    @Test
    void verify_shouldNotAcceptACodeIssuedForAnotherPurpose() {
        service.sendCode(EmailCodePurpose.REGISTER, EMAIL, IP);
        String registrationCode = store.get(EmailCodePurpose.REGISTER, EMAIL).code();

        assertNull(store.get(EmailCodePurpose.PASSWORD_RESET, EMAIL));
        BizException error = assertThrows(BizException.class,
            () -> service.verify(EmailCodePurpose.PASSWORD_RESET, EMAIL, registrationCode));

        assertEquals(ResultCode.EMAIL_CODE_REISSUE_REQUIRED, error.getResultCode());
        assertNotNull(store.get(EmailCodePurpose.REGISTER, EMAIL), "另一条链路的失败不该消耗这份码");
        assertEquals(0, store.get(EmailCodePurpose.REGISTER, EMAIL).attempts());
    }

    /**
     * 而发信额度<b>刻意</b>跨用途共用。
     *
     * <p>各给一份的话，攻击者交替调用注册发码与重置发码，对同一个受害者邮箱的发信量就翻倍——
     * 这几道限制保护的是收件人不被轰炸，与发的是哪种码无关。</p>
     */
    @Test
    void sendCode_shouldShareTheSendingQuotaAcrossPurposes() {
        service.sendCode(EmailCodePurpose.REGISTER, EMAIL, IP);

        BizException error = assertThrows(BizException.class,
            () -> service.sendCode(EmailCodePurpose.PASSWORD_RESET, EMAIL, IP));

        assertEquals(ResultCode.EMAIL_CODE_TOO_FREQUENT, error.getResultCode());
        verify(mailSender, times(1)).send(any(), any(), any());
    }

    /** 用途决定邮件主题与正文口径，两封信不能长得一模一样。 */
    @Test
    void sendCode_shouldUsePurposeSpecificSubjectAndWording() {
        service.sendCode(EmailCodePurpose.PASSWORD_RESET, EMAIL, IP);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(any(), subject.capture(), body.capture());
        assertEquals("密码重置验证码", subject.getValue());
        assertTrue(body.getValue().contains("重置"), "正文要说清这是重置而不是注册：" + body.getValue());
    }

    private String wrongCodeFor(String code) {
        return "000000".equals(code) ? "111111" : "000000";
    }
}
