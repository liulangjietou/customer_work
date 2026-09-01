package com.richard.fyoung.customeradmin.auth.service;

import com.richard.fyoung.customeradmin.auth.dto.PasswordResetEmailCodeRequest;
import com.richard.fyoung.customeradmin.auth.dto.PasswordResetRequest;
import com.richard.fyoung.customeradmin.auth.email.EmailCodePurpose;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationCode;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationStore;
import com.richard.fyoung.customeradmin.auth.email.InMemoryEmailVerificationStore;
import com.richard.fyoung.customeradmin.auth.guard.CaptchaService;
import com.richard.fyoung.customeradmin.auth.guard.LoginAttemptGuard;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.notify.AdminMailSender;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭注册邮箱找回登录密码。
 *
 * <p>这条链路的多数用例验的不是"功能对不对"，而是<b>两种结果是否真的无法区分</b>：
 * 一个匿名接口只要在"账号存在"与"不存在"之间露出任何差别——返回值、错误码、
 * 有没有被限流——就成了账号与邮箱的关联查询服务。下面逐条钉住这些差别不存在。</p>
 */
class PasswordResetServiceTest {

    private static final String USERNAME = "richard";
    private static final String EMAIL = "richard@example.com";
    private static final String IP = "203.0.113.31";
    private static final String NEW_PASSWORD = "Reset2026pwd";
    private static final String ENCODED = "$2a$10$encoded";
    private static final long USER_ID = 42L;

    private SysUserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private CaptchaService captchaService;
    private AdminMailSender mailSender;
    private SessionRevocationService sessionRevocationService;
    private EmailVerificationStore emailStore;
    private RegistrationGuardProperties properties;
    private WindowCounter counter;
    private LoginAttemptGuard loginAttemptGuard;
    private OperationLogMapper operationLogMapper;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        captchaService = mock(CaptchaService.class);
        mailSender = mock(AdminMailSender.class);
        sessionRevocationService = mock(SessionRevocationService.class);
        operationLogMapper = mock(OperationLogMapper.class);
        emailStore = new InMemoryEmailVerificationStore();
        properties = new RegistrationGuardProperties();
        properties.getLoginLock().setEnabled(true);
        counter = new InMemoryWindowCounter();
        loginAttemptGuard = new LoginAttemptGuard(properties.getLoginLock(),
            new PublicDeploymentProperties(), counter);

        when(captchaService.verify(any(), any())).thenReturn(true);
        when(mailSender.available()).thenReturn(true);
        when(mailSender.platformName()).thenReturn("客服智能体平台");
        when(passwordEncoder.encode(any())).thenReturn(ENCODED);
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);
        when(userMapper.incrementAuthEpoch(anyLong())).thenReturn(1);

        // 真实的 EmailVerificationService 而不是 mock：这条链路要验的正是"额度到底扣没扣"、
        // "验证码到底废没废"，注了 mock 只能验到"Service 记得调它"
        service = new PasswordResetService(userMapper, passwordEncoder,
            new EmailVerificationService(properties, emailStore, mailSender, counter),
            captchaService, mailSender, sessionRevocationService, loginAttemptGuard,
            operationLogMapper);
    }

    // ---------- 发码 ----------

    @Test
    void sendCode_shouldMailAResetCodeToTheMatchingAccount() {
        givenAccount(localUser());

        int ttl = service.sendCode(codeRequest(USERNAME, EMAIL), IP);

        assertEquals(properties.getEmailVerification().getTtlSeconds(), ttl);
        EmailVerificationCode stored = emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL);
        assertNotNull(stored, "匹配的账号应当收到一份重置码");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq(EMAIL), subject.capture(), body.capture());
        assertEquals(EmailCodePurpose.PASSWORD_RESET.mailSubject(), subject.getValue());
        assertTrue(body.getValue().contains(stored.code()), "正文里必须带上那串码");
    }

    /**
     * 重置邮件同样不带链接。
     *
     * <p>"点此重置密码"正是钓鱼邮件最常用的幌子，我们自己发的信里出现可点链接，
     * 就是在教用户点这类链接。</p>
     */
    @Test
    void sendCode_shouldNotPutAnyClickableLinkInMailBody() {
        givenAccount(localUser());

        service.sendCode(codeRequest(USERNAME, EMAIL), IP);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(any(), any(), body.capture());
        assertTrue(!body.getValue().contains("http://") && !body.getValue().contains("https://"),
            "重置验证码邮件正文不应出现链接：" + body.getValue());
    }

    @Test
    void sendCode_shouldStaySilentWhenUsernameIsUnknown() {
        givenAccount(null);

        int ttl = service.sendCode(codeRequest("nobody", EMAIL), IP);

        assertEquals(properties.getEmailVerification().getTtlSeconds(), ttl,
            "返回值必须与账号存在时完全一致");
        assertNull(emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL));
        verify(mailSender, never()).send(any(), any(), any());
    }

    /**
     * 用户名存在、邮箱却是别人的——同样一声不吭。
     *
     * <p>这一条正是"用户名 + 邮箱都要对上"的意义所在：知道用户名不足以让验证码发到自己手里。</p>
     */
    @Test
    void sendCode_shouldStaySilentWhenEmailBelongsToSomeoneElse() {
        givenAccount(localUser());

        service.sendCode(codeRequest(USERNAME, "attacker@example.com"), IP);

        assertNull(emailStore.get(EmailCodePurpose.PASSWORD_RESET, "attacker@example.com"));
        assertNull(emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL));
        verify(mailSender, never()).send(any(), any(), any());
    }

    /**
     * 账号不匹配也要照扣发信额度。
     *
     * <p>先查账号再扣额度的话，"这次有没有被冷却挡住"本身就成了账号存在性探针——
     * 前面那些含糊的返回值也就白设计了。</p>
     */
    @Test
    void sendCode_shouldConsumeSendQuotaEvenWhenTheAccountDoesNotMatch() {
        when(userMapper.selectOne(any())).thenReturn(null).thenReturn(localUser());

        service.sendCode(codeRequest("nobody", EMAIL), IP);
        BizException error = assertThrows(BizException.class,
            () -> service.sendCode(codeRequest(USERNAME, EMAIL), IP));

        assertEquals(ResultCode.EMAIL_CODE_TOO_FREQUENT, error.getResultCode());
        verify(mailSender, never()).send(any(), any(), any());
    }

    /**
     * OA 域账号：发一封说明信，而不是在响应里说明。
     *
     * <p>不发信的话，OA 用户会一直等一封永远不会到的验证码；而在响应里讲原因，
     * 等于给了任何人一个"这个账号是不是域账号"的查询接口。信只有邮箱的主人收得到。</p>
     */
    @Test
    void sendCode_shouldMailAnExplanationInsteadOfACodeForDirectoryAccounts() {
        SysUser ldap = localUser();
        ldap.setLoginType(SysUser.LOGIN_TYPE_LDAP);
        ldap.setPassword(null);
        givenAccount(ldap);

        int ttl = service.sendCode(codeRequest(USERNAME, EMAIL), IP);

        assertEquals(properties.getEmailVerification().getTtlSeconds(), ttl);
        assertNull(emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL), "域账号不该拿到重置码");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq(EMAIL), any(), body.capture());
        assertTrue(body.getValue().contains("OA"), "说明信要讲清楚为什么没有验证码：" + body.getValue());
    }

    @Test
    void sendCode_shouldMailAnExplanationInsteadOfACodeForDisabledAccounts() {
        SysUser disabled = localUser();
        disabled.setStatus(0);
        givenAccount(disabled);

        service.sendCode(codeRequest(USERNAME, EMAIL), IP);

        assertNull(emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq(EMAIL), any(), body.capture());
        assertTrue(body.getValue().contains("禁用"), "说明信要讲清楚为什么没有验证码：" + body.getValue());
    }

    /**
     * 图形验证码排在最前，且不看部署形态。
     *
     * <p>发信是唯一会向站外第三方产生副作用的匿名操作；被图形码挡下的请求不该扣掉发信额度，
     * 否则脚本随便打几发就能把真人的额度耗光。</p>
     */
    @Test
    void sendCode_shouldRejectWrongCaptchaWithoutSpendingAnything() {
        when(captchaService.verify(any(), any())).thenReturn(false);
        givenAccount(localUser());

        BizException error = assertThrows(BizException.class,
            () -> service.sendCode(codeRequest(USERNAME, EMAIL), IP));

        assertEquals(ResultCode.CAPTCHA_INVALID, error.getResultCode());
        verify(mailSender, never()).send(any(), any(), any());
        verify(userMapper, never()).selectOne(any());

        // 额度没被扣：紧接着的一次正常请求应当照常发码，而不是被冷却挡住
        when(captchaService.verify(any(), any())).thenReturn(true);
        assertDoesNotThrow(() -> service.sendCode(codeRequest(USERNAME, EMAIL), IP));
    }

    @Test
    void sendCode_shouldRefuseWhenMailIsNotAvailable() {
        when(mailSender.available()).thenReturn(false);

        BizException error = assertThrows(BizException.class,
            () -> service.sendCode(codeRequest(USERNAME, EMAIL), IP));

        assertEquals(ResultCode.FEATURE_NOT_AVAILABLE, error.getResultCode());
    }

    /** 大小写不同的同一个邮箱是同一个人，两侧都要走同一份归一口径。 */
    @Test
    void sendCode_shouldTreatEmailCaseInsensitively() {
        givenAccount(localUser());

        service.sendCode(codeRequest(USERNAME, "Richard@Example.COM"), IP);

        assertNotNull(emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL));
    }

    // ---------- 重置 ----------

    @Test
    void reset_shouldUpdatePasswordBumpEpochAndRevokeSessions() {
        givenAccount(localUser());
        String code = issuedCode();

        service.reset(resetRequest(USERNAME, EMAIL, code, NEW_PASSWORD, NEW_PASSWORD), IP);

        ArgumentCaptor<SysUser> saved = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(saved.capture());
        assertEquals(USER_ID, saved.getValue().getId());
        assertEquals(ENCODED, saved.getValue().getPassword());
        assertEquals(SysUser.EMAIL_VERIFIED, saved.getValue().getEmailVerified(),
            "能收到发往该地址的验证码并填回来，本身就是一次邮箱所有权证明");
        assertNull(saved.getValue().getUsername(), "只更新该更新的列，不要把整行回写回去");
        verify(userMapper).incrementAuthEpoch(USER_ID);
        verify(sessionRevocationService).revokeUserAfterCommit(USER_ID);
        assertNull(emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL), "用过的码必须作废");
    }

    /**
     * 重置成功要顺带解掉登录锁定。
     *
     * <p>真实用户往往正是被自己输错的那几次锁在门外，才想起来重置密码；
     * 不清的话他改完密码还得干等锁定窗口过去，这个功能只解决了一半问题。</p>
     */
    @Test
    void reset_shouldClearLoginLockSoTheUserCanSignInImmediately() {
        givenAccount(localUser());
        for (int i = 0; i < properties.getLoginLock().getMaxFailures(); i++) {
            loginAttemptGuard.recordFailure(USERNAME, IP);
        }
        assertThrows(BizException.class, () -> loginAttemptGuard.checkNotLocked(USERNAME, IP));

        service.reset(resetRequest(USERNAME, EMAIL, issuedCode(), NEW_PASSWORD, NEW_PASSWORD), IP);

        assertDoesNotThrow(() -> loginAttemptGuard.checkNotLocked(USERNAME, IP));
    }

    /**
     * 账号对不上时不能碰那份真验证码的重试次数。
     *
     * <p>否则任何人拿一个错的用户名反复提交，就能把受害者手里的码打废——
     * 替他把找回密码这条路堵死。</p>
     */
    @Test
    void reset_shouldRejectMismatchedAccountWithoutConsumingTheRealCode() {
        when(userMapper.selectOne(any())).thenReturn(null).thenReturn(localUser());
        String code = issuedCode();

        BizException error = assertThrows(BizException.class,
            () -> service.reset(resetRequest("nobody", EMAIL, code, NEW_PASSWORD, NEW_PASSWORD), IP));

        assertEquals(ResultCode.PASSWORD_RESET_REJECTED, error.getResultCode());
        EmailVerificationCode stored = emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL);
        assertNotNull(stored, "码还在");
        assertEquals(0, stored.attempts(), "别人的错误尝试不该记在这份码头上");

        // 真正的主人仍然能用同一份码把密码改掉
        assertDoesNotThrow(() ->
            service.reset(resetRequest(USERNAME, EMAIL, code, NEW_PASSWORD, NEW_PASSWORD), IP));
    }

    /**
     * "码填错了"与"这个组合压根没发过码"必须给出同一个响应。
     *
     * <p>分开报的话，攻击者随便填一个验证码，看返回的是哪一种，就能试出
     * "这个用户名和这个邮箱是不是一对"——只有真发过码的组合才会说"码错了"。</p>
     */
    @Test
    void reset_shouldReportTheSameRejectionForWrongCodeAndForNeverIssuedCode() {
        givenAccount(localUser());
        String code = issuedCode();

        BizException wrongCode = assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, wrongCodeFor(code), NEW_PASSWORD, NEW_PASSWORD), IP));

        emailStore.invalidate(EmailCodePurpose.PASSWORD_RESET, EMAIL);
        BizException neverIssued = assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, wrongCodeFor(code), NEW_PASSWORD, NEW_PASSWORD), IP));

        assertEquals(ResultCode.PASSWORD_RESET_REJECTED, wrongCode.getResultCode());
        assertEquals(ResultCode.PASSWORD_RESET_REJECTED, neverIssued.getResultCode());
        assertEquals(wrongCode.getMessage(), neverIssued.getMessage(), "连文案都不能有差别");
    }

    /**
     * 注册验证码不能拿来重置密码。
     *
     * <p>两者按同一个收件人存储，不按用途分键的话，一封注册验证码就能改掉
     * 同一邮箱下账号的密码——而注册码是任何人对着一个未注册邮箱都能索取的。</p>
     */
    @Test
    void reset_shouldNotAcceptARegistrationCode() {
        givenAccount(localUser());
        String registrationCode = "654321";
        emailStore.save(EmailCodePurpose.REGISTER, EMAIL, new EmailVerificationCode(
            registrationCode, 0, System.currentTimeMillis() + 600_000));

        BizException error = assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, registrationCode, NEW_PASSWORD, NEW_PASSWORD), IP));

        assertEquals(ResultCode.PASSWORD_RESET_REJECTED, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
        assertNotNull(emailStore.get(EmailCodePurpose.REGISTER, EMAIL), "注册码不该被这条链路消耗");
    }

    /** 表单校验排在核验之前：真人把两次密码敲得不一致，不该白白废掉手里那份码。 */
    @Test
    void reset_shouldRejectMismatchedConfirmationBeforeTouchingTheCode() {
        givenAccount(localUser());
        String code = issuedCode();

        BizException error = assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, code, NEW_PASSWORD, NEW_PASSWORD + "x"), IP));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        assertEquals(0, emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL).attempts());
    }

    @Test
    void reset_shouldRejectWeakPasswordBeforeTouchingTheCode() {
        givenAccount(localUser());
        String code = issuedCode();

        BizException error = assertThrows(BizException.class,
            () -> service.reset(resetRequest(USERNAME, EMAIL, code, "password", "password"), IP));

        assertEquals(ResultCode.PASSWORD_TOO_WEAK, error.getResultCode());
        assertEquals(0, emailStore.get(EmailCodePurpose.PASSWORD_RESET, EMAIL).attempts());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    /**
     * 域账号即使手里有一份有效的重置码也不能改。
     *
     * <p>它的密码根本不在本表里，改了只会留下一行谁也用不上的哈希，
     * 而下次 OA 登录仍然走域控——用户会以为自己改成功了。</p>
     */
    @Test
    void reset_shouldRefuseDirectoryAccountEvenWithAValidCode() {
        SysUser ldap = localUser();
        ldap.setLoginType(SysUser.LOGIN_TYPE_LDAP);
        givenAccount(ldap);
        emailStore.save(EmailCodePurpose.PASSWORD_RESET, EMAIL,
            new EmailVerificationCode("123456", 0, System.currentTimeMillis() + 600_000));

        BizException error = assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, "123456", NEW_PASSWORD, NEW_PASSWORD), IP));

        assertEquals(ResultCode.PASSWORD_RESET_REJECTED, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void reset_shouldRefuseDisabledAccountEvenWithAValidCode() {
        SysUser disabled = localUser();
        disabled.setStatus(0);
        givenAccount(disabled);
        emailStore.save(EmailCodePurpose.PASSWORD_RESET, EMAIL,
            new EmailVerificationCode("123456", 0, System.currentTimeMillis() + 600_000));

        BizException error = assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, "123456", NEW_PASSWORD, NEW_PASSWORD), IP));

        assertEquals(ResultCode.PASSWORD_RESET_REJECTED, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    /**
     * 认证版本没能递增就必须整体失败。
     *
     * <p>只改了 password 却没撤权，等于密码换了、旧会话还活着——
     * 而"我怀疑号被盗了"恰恰是重置密码最常见的动机。</p>
     */
    @Test
    void reset_shouldFailWhenAuthEpochCannotBeBumped() {
        givenAccount(localUser());
        when(userMapper.incrementAuthEpoch(anyLong())).thenReturn(0);
        String code = issuedCode();

        BizException error = assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, code, NEW_PASSWORD, NEW_PASSWORD), IP));

        assertEquals(ResultCode.PASSWORD_RESET_REJECTED, error.getResultCode());
        verify(sessionRevocationService, never()).revokeUserAfterCommit(anyLong());
    }

    /**
     * 成功的重置要在操作日志表里留痕。
     *
     * <p>"某账号的密码在什么时候被从哪个 IP 重置了"是要能事后查的安全事件，
     * 而运营查得到的是后台那张表，不是应用日志。</p>
     */
    @Test
    void reset_shouldRecordTheSuccessfulResetInTheOperationLog() {
        givenAccount(localUser());

        service.reset(resetRequest(USERNAME, EMAIL, issuedCode(), NEW_PASSWORD, NEW_PASSWORD), IP);

        ArgumentCaptor<SysOperationLog> logged = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(operationLogMapper).insert(logged.capture());
        assertEquals(USER_ID, logged.getValue().getUserId());
        assertEquals(USERNAME, logged.getValue().getUsername());
        assertEquals("重置密码", logged.getValue().getOperation());
        assertEquals(IP, logged.getValue().getIp());
        assertEquals(SysOperationLog.RESULT_SUCCESS, logged.getValue().getResult());
    }

    /**
     * 失败的尝试刻意<b>不</b>写进日志表。
     *
     * <p>整条链路都在向调用方隐藏"这个用户名和这个邮箱是不是一对"，
     * 把每次失败逐条记下来等于换个地方把它记住了。</p>
     */
    @Test
    void reset_shouldNotRecordRejectedAttempts() {
        givenAccount(localUser());

        assertThrows(BizException.class, () -> service.reset(
            resetRequest(USERNAME, EMAIL, "999999", NEW_PASSWORD, NEW_PASSWORD), IP));

        verify(operationLogMapper, never()).insert(any(SysOperationLog.class));
    }

    /**
     * 留痕失败不能连累已经完成的改密。
     *
     * <p>密码已经改了，这时抛异常只会造出"用户以为失败、其实已改"的最坏状态——
     * 他会拿旧密码反复登录，直到把自己锁掉。</p>
     */
    @Test
    void reset_shouldSucceedEvenIfTheOperationLogCannotBeWritten() {
        givenAccount(localUser());
        when(operationLogMapper.insert(any(SysOperationLog.class))).thenThrow(new IllegalStateException("log table down"));

        assertDoesNotThrow(() -> service.reset(
            resetRequest(USERNAME, EMAIL, issuedCode(), NEW_PASSWORD, NEW_PASSWORD), IP));

        verify(userMapper).updateById(any(SysUser.class));
        verify(sessionRevocationService).revokeUserAfterCommit(USER_ID);
    }

    /**
     * 重置这一步不要求邮件可用。
     *
     * <p>核验与改密都不发信，SMTP 抖动不该把已经拿到验证码的人挡在门外——
     * 那正是他最需要这个功能的时刻。</p>
     */
    @Test
    void reset_shouldWorkWhileMailIsTemporarilyDown() {
        givenAccount(localUser());
        String code = issuedCode();
        when(mailSender.available()).thenReturn(false);

        assertDoesNotThrow(() ->
            service.reset(resetRequest(USERNAME, EMAIL, code, NEW_PASSWORD, NEW_PASSWORD), IP));

        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    void available_shouldFollowMailAvailabilityWithoutAnySwitchOfItsOwn() {
        when(mailSender.available()).thenReturn(true);
        assertTrue(service.available());

        when(mailSender.available()).thenReturn(false);
        assertTrue(!service.available());
    }

    // ---------- 辅助 ----------

    private void givenAccount(SysUser user) {
        when(userMapper.selectOne(any())).thenReturn(user);
    }

    private SysUser localUser() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setEmail(EMAIL);
        user.setTenantId("default");
        user.setLoginType(SysUser.LOGIN_TYPE_LOCAL);
        user.setStatus(1);
        user.setEmailVerified(SysUser.EMAIL_UNVERIFIED);
        return user;
    }

    /** 直接写入一份有效重置码，绕开发码链路的限流，让重置侧的用例互不干扰。 */
    private String issuedCode() {
        String code = "123456";
        emailStore.save(EmailCodePurpose.PASSWORD_RESET, EMAIL,
            new EmailVerificationCode(code, 0, System.currentTimeMillis() + 600_000));
        return code;
    }

    private String wrongCodeFor(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private PasswordResetEmailCodeRequest codeRequest(String username, String email) {
        return new PasswordResetEmailCodeRequest(username, email, "captcha-id", "abcd");
    }

    private PasswordResetRequest resetRequest(String username, String email, String code,
                                              String password, String confirm) {
        return new PasswordResetRequest(username, email, code, password, confirm);
    }
}
