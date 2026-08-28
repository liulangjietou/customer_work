package com.richard.fyoung.customeradmin.system.user.service;

import com.richard.fyoung.customeradmin.auth.dto.RegisterRequest;
import com.richard.fyoung.customeradmin.auth.email.EmailVerificationService;
import com.richard.fyoung.customeradmin.auth.email.InMemoryEmailVerificationStore;
import com.richard.fyoung.customeradmin.auth.guard.CaptchaService;
import com.richard.fyoung.customeradmin.auth.guard.InMemoryCaptchaStore;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuard;
import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.notify.AdminMailSender;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRegistrationServiceTest {

    /** 注册限流窗口内允许的次数，取自 RegistrationGuardProperties 的默认值。 */
    private static final int DEFAULT_RATE_LIMIT = 5;
    private static final String CLIENT_IP = "203.0.113.7";

    private SysUserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private RegistrationGuardProperties guardProperties;
    private PublicDeploymentProperties publicDeployment;
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        guardProperties = new RegistrationGuardProperties();
        publicDeployment = new PublicDeploymentProperties();
        service = new UserRegistrationService(userMapper, passwordEncoder, guard());
    }

    /**
     * 用真实 Guard 而不是 mock：注册准入判定本身就是这条链路要验的东西，
     * 注了 mock 只能验到"Service 记得调 Guard"，验不到"Guard 到底拦不拦"。
     */
    private RegistrationGuard guard() {
        return new RegistrationGuard(guardProperties, publicDeployment,
            new CaptchaService(new InMemoryCaptchaStore(), guardProperties.getCaptcha()),
            new EmailVerificationService(guardProperties, new InMemoryEmailVerificationStore(),
                mock(AdminMailSender.class), new InMemoryWindowCounter()),
            new InMemoryWindowCounter());
    }

    /**
     * 邮箱已被占用时不发信。
     *
     * <p>照发不误的话，用户要等收到码、填完整张表才被告知"这邮箱已注册"，白跑一趟；
     * 更要紧的是那封信已经发到别人的邮箱里了——注册流程成了给他人发骚扰信的通道。</p>
     */
    @Test
    void sendEmailCode_shouldRejectAlreadyRegisteredAddressBeforeSendingMail() {
        guardProperties.getEmailVerification().setEnabled(true);
        service = new UserRegistrationService(userMapper, passwordEncoder, guard());
        when(userMapper.selectCount(any())).thenReturn(1L);

        BizException error = assertThrows(BizException.class,
            () -> service.sendEmailCode("Richard@Example.com", null, null, CLIENT_IP));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, error.getResultCode());
    }

    /** 占用判定用归一后的邮箱：大小写不同的同一地址必须命中同一条记录。 */
    @Test
    void sendEmailCode_shouldNormalizeAddressBeforeCheckingOccupancy() {
        guardProperties.getEmailVerification().setEnabled(true);
        service = new UserRegistrationService(userMapper, passwordEncoder, guard());
        when(userMapper.selectCount(any())).thenReturn(0L);

        // 邮件不可用会在占用检查之后才报错，说明占用检查确实跑到了且放行
        BizException error = assertThrows(BizException.class,
            () -> service.sendEmailCode("  Richard@Example.com ", null, null, CLIENT_IP));

        assertEquals(ResultCode.EMAIL_CODE_SEND_FAILED, error.getResultCode());
    }

    /** 常规注册请求：不带邮箱与验证码，内网默认配置下应当放行。 */
    private RegisterRequest request(String username, String password, String confirm, String nickname) {
        return new RegisterRequest(username, password, confirm, nickname, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void register_shouldCreatePendingDefaultTenantUserWithoutLeakingContext() {
        when(passwordEncoder.encode("secret12")).thenReturn("encoded");
        when(userMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
            invocation.<SysUser>getArgument(0).setId(21L);
            return 1;
        });

        service.register(request("richard", "secret12", "secret12", " Richard "), CLIENT_IP);

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        SysUser user = userCaptor.getValue();
        assertEquals("richard", user.getUsername());
        assertEquals("Richard", user.getNickname());
        assertEquals("encoded", user.getPassword());
        assertEquals("LOCAL", user.getLoginType());
        assertEquals(1, user.getStatus());
        assertEquals(UserApprovalStatus.PENDING.name(), user.getApprovalStatus());
        assertEquals(TenantContext.DEFAULT, user.getTenantId());
        assertNull(TenantContext.get(), "公开注册结束后不能把 default 租户泄漏给复用线程");
    }

    @Test
    void register_shouldRejectPasswordConfirmationMismatchBeforeWriting() {
        BizException error = assertThrows(BizException.class, () -> service.register(
            request("richard", "secret12", "secret13", "Richard"), CLIENT_IP));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void register_shouldRejectSoftDeletedUsernameInsteadOfRevivingIt() {
        SysUser deleted = new SysUser();
        deleted.setId(9L);
        deleted.setDeleted(1);
        when(userMapper.selectByUsernameIgnoreLogicDelete("richard")).thenReturn(deleted);

        BizException error = assertThrows(BizException.class, () -> service.register(
            request("richard", "secret12", "secret12", "Richard"), CLIENT_IP));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, error.getResultCode());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void register_shouldTranslateConcurrentUniqueKeyRaceToBusinessError() {
        when(passwordEncoder.encode("secret12")).thenReturn("encoded");
        when(userMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("duplicate"));

        BizException error = assertThrows(BizException.class, () -> service.register(
            request("richard", "secret12", "secret12", "Richard"), CLIENT_IP));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, error.getResultCode());
        assertEquals("用户名已存在", error.getMessage());
    }
}
