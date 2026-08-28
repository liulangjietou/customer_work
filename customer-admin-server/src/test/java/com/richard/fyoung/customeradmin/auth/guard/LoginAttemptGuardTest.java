package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 登录失败锁定。
 *
 * <p>这几条断言钉住的是"防爆破不能变成拒绝服务"这条边界：锁定维度是账号与来源 IP 的组合，
 * 被锁期间不再累加，成功后计数清零。</p>
 */
class LoginAttemptGuardTest {

    private static final String USER = "richard";
    private static final String IP = "203.0.113.11";

    private RegistrationGuardProperties properties;
    private PublicDeploymentProperties publicDeployment;
    private LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        properties = new RegistrationGuardProperties();
        properties.getLoginLock().setEnabled(true);
        publicDeployment = new PublicDeploymentProperties();
        guard = new LoginAttemptGuard(properties.getLoginLock(), publicDeployment,
            new InMemoryWindowCounter());
    }

    @Test
    void checkNotLocked_shouldRejectAfterReachingFailureThreshold() {
        for (int i = 0; i < properties.getLoginLock().getMaxFailures(); i++) {
            guard.recordFailure(USER, IP);
        }

        BizException error = assertThrows(BizException.class, () -> guard.checkNotLocked(USER, IP));

        assertEquals(ResultCode.LOGIN_LOCKED, error.getResultCode());
    }

    /**
     * 只锁"账号+IP"的组合。
     *
     * <p>只锁账号的话，任何人拿一个已知用户名连打几次就能把真实用户挡在门外——
     * 防爆破的措施反而成了拒绝服务的手段。</p>
     */
    @Test
    void checkNotLocked_shouldScopeLockToUsernameAndAddressPair() {
        for (int i = 0; i < properties.getLoginLock().getMaxFailures(); i++) {
            guard.recordFailure(USER, IP);
        }

        assertThrows(BizException.class, () -> guard.checkNotLocked(USER, IP));
        assertDoesNotThrow(() -> guard.checkNotLocked(USER, "198.51.100.8"));
        assertDoesNotThrow(() -> guard.checkNotLocked("someone-else", IP));
    }

    /** 被锁期间的尝试不再累加，否则锁定期会被无限延长，真实用户永远等不到解锁。 */
    @Test
    void checkNotLocked_shouldNotExtendLockWindowOnFurtherAttempts() {
        for (int i = 0; i < properties.getLoginLock().getMaxFailures(); i++) {
            guard.recordFailure(USER, IP);
        }
        InMemoryWindowCounter counter = new InMemoryWindowCounter();
        LoginAttemptGuard readOnlyGuard = new LoginAttemptGuard(properties.getLoginLock(),
            publicDeployment, counter);
        for (int i = 0; i < properties.getLoginLock().getMaxFailures(); i++) {
            readOnlyGuard.recordFailure(USER, IP);
        }
        long before = counter.current(
            "admin:login:fail:" + USER + ":" + IP, properties.getLoginLock().getWindowSeconds());

        assertThrows(BizException.class, () -> readOnlyGuard.checkNotLocked(USER, IP));
        assertThrows(BizException.class, () -> readOnlyGuard.checkNotLocked(USER, IP));

        assertEquals(before, counter.current(
            "admin:login:fail:" + USER + ":" + IP, properties.getLoginLock().getWindowSeconds()));
    }

    /** 窗口内失败几次后成功的正常用户不该带着计数继续跑。 */
    @Test
    void recordSuccess_shouldResetFailureCounter() {
        for (int i = 0; i < properties.getLoginLock().getMaxFailures() - 1; i++) {
            guard.recordFailure(USER, IP);
        }
        guard.recordSuccess(USER, IP);

        for (int i = 0; i < properties.getLoginLock().getMaxFailures() - 1; i++) {
            guard.recordFailure(USER, IP);
        }

        assertDoesNotThrow(() -> guard.checkNotLocked(USER, IP));
    }

    /** 用户名大小写不该把同一个人分裂成两个计数桶。 */
    @Test
    void checkNotLocked_shouldNormalizeUsernameCase() {
        for (int i = 0; i < properties.getLoginLock().getMaxFailures(); i++) {
            guard.recordFailure("Richard", IP);
        }

        assertThrows(BizException.class, () -> guard.checkNotLocked("richard", IP));
    }

    @Test
    void guard_shouldStayInactiveWhenDisabledOnInternalDeployment() {
        properties.getLoginLock().setEnabled(false);

        for (int i = 0; i < 50; i++) {
            guard.recordFailure(USER, IP);
        }

        assertDoesNotThrow(() -> guard.checkNotLocked(USER, IP));
    }

    /** 对外部署强制启用，配置里关掉也不生效。 */
    @Test
    void guard_shouldBeForcedOnPublicDeployment() {
        properties.getLoginLock().setEnabled(false);
        publicDeployment.setEnabled(true);

        for (int i = 0; i < properties.getLoginLock().getMaxFailures(); i++) {
            guard.recordFailure(USER, IP);
        }

        assertThrows(BizException.class, () -> guard.checkNotLocked(USER, IP));
    }
}
