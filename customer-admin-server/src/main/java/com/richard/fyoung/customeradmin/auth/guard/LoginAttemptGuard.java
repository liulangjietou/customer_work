package com.richard.fyoung.customeradmin.auth.guard;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 登录失败锁定：连续失败达到阈值后，在窗口期内拒绝该"账号+来源 IP"的登录尝试。
 *
 * <p><b>锁定维度是账号与 IP 的组合，不是账号本身</b>。只锁账号的话，任何人都能拿一个
 * 已知用户名连打几次，把真实用户锁在门外——防爆破的措施反而成了拒绝服务的手段。
 * 只锁 IP 则挡不住分布式撞库里"每个 IP 只试少数几个账号"的打法。组合锁定对两者都有效，
 * 代价是同一个 NAT 出口下的多个用户会互相影响，这在对外部署里可接受。</p>
 *
 * <p><b>成功即清零</b>：窗口内失败四次、第五次成功的正常用户不该带着计数继续跑，
 * 否则他下次偶尔输错一次就被锁。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class LoginAttemptGuard {

    private static final String KEY_PREFIX = "admin:login:fail:";

    private final RegistrationGuardProperties.LoginLock config;
    private final PublicDeploymentProperties publicDeployment;
    private final WindowCounter counter;

    public LoginAttemptGuard(RegistrationGuardProperties.LoginLock config,
                             PublicDeploymentProperties publicDeployment,
                             WindowCounter counter) {
        this.config = config;
        this.publicDeployment = publicDeployment;
        this.counter = counter;
    }

    /** 对外实例强制启用，内网实例按配置。 */
    public boolean enabled() {
        return publicDeployment.isEnabled() || config.isEnabled();
    }

    /**
     * 登录前判定，已锁定则直接拒绝。
     *
     * <p>只读计数、不累加：被锁期间的每次尝试都累加的话，锁定期会被无限延长，
     * 真实用户永远等不到解锁。</p>
     */
    public void checkNotLocked(String username, String clientIp) {
        if (!enabled()) {
            return;
        }
        long failures = counter.current(key(username, clientIp), config.getWindowSeconds());
        if (failures >= config.getMaxFailures()) {
            log.info("login rejected by attempt lock, username={}, ip={}, failures={}",
                username, clientIp, failures);
            throw new BizException(ResultCode.LOGIN_LOCKED);
        }
    }

    /** 记一次失败。达到阈值后由下一次 {@link #checkNotLocked} 拒绝。 */
    public void recordFailure(String username, String clientIp) {
        if (!enabled()) {
            return;
        }
        long failures = counter.increment(key(username, clientIp), 1, config.getWindowSeconds());
        if (failures >= config.getMaxFailures()) {
            log.info("login attempt lock engaged, username={}, ip={}, windowSeconds={}",
                username, clientIp, config.getWindowSeconds());
        }
    }

    /** 登录成功，清零该组合的失败计数。 */
    public void recordSuccess(String username, String clientIp) {
        if (!enabled()) {
            return;
        }
        String key = key(username, clientIp);
        long current = counter.current(key, config.getWindowSeconds());
        if (current > 0) {
            counter.decrement(key, current, config.getWindowSeconds());
        }
    }

    /** 用户名统一小写：登录本身对大小写的处理由认证链路决定，计数键不该因大小写分裂成两个桶。 */
    private String key(String username, String clientIp) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return KEY_PREFIX + normalized + ":" + clientIp;
    }
}
