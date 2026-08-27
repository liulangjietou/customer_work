package com.richard.fyoung.customeradmin.auth.guard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 自助注册与登录的防滥用参数。
 *
 * <p>内网实例可以整体关掉（默认即关），对外开放实例由
 * {@code admin.public-deployment.enabled=true} 强制打开——见
 * {@code RegistrationGuard#captchaRequired()}，那里不看本类的开关。
 * 理由是这几项一旦漏配，注册接口就是个匿名可打的免费入口。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "admin.registration")
public class RegistrationGuardProperties {

    /** 是否开启自助注册。关闭后注册接口直接拒绝（仅限管理员预建账号）。 */
    private boolean selfServiceEnabled = true;

    /** 是否要求图形验证码。对外部署强制为 true，此处配置只对内网实例生效。 */
    private boolean captchaEnabled = false;

    /** 注册是否必须填写邮箱。对外部署强制为 true。 */
    private boolean emailRequired = false;

    @NestedConfigurationProperty
    private RateLimit rateLimit = new RateLimit();

    @NestedConfigurationProperty
    private Captcha captcha = new Captcha();

    @NestedConfigurationProperty
    private LoginLock loginLock = new LoginLock();

    /**
     * 是否信任反向代理写入的 {@code X-Forwarded-For}。
     *
     * <p>默认信任：对外实例必然部署在网关/Nginx 之后，不信任的话所有请求的来源地址
     * 都是同一个反代 IP，限流会把全体用户按一个桶算。<b>运维侧的前提是反代必须
     * 覆写而不是追加该请求头</b>，否则客户端可以自己伪造首段绕开 IP 限流。
     * 直连暴露的部署要把它设为 false。</p>
     */
    private boolean trustForwardedHeader = true;

    /** 按来源 IP 的注册频率上限。 */
    @Getter
    @Setter
    public static class RateLimit {

        /** 单个 IP 在窗口内允许的注册请求次数。 */
        private int maxAttempts = 5;

        /** 统计窗口（秒），默认 1 小时。 */
        private int windowSeconds = 3600;
    }

    /** 图形验证码参数。 */
    @Getter
    @Setter
    public static class Captcha {

        /** 验证码位数。 */
        private int length = 4;

        /** 有效期（秒）。过短会让填表慢的人反复失败，过长等于给撞库留窗口。 */
        private int ttlSeconds = 180;

        /** 图片宽度（像素）。 */
        private int width = 120;

        /** 图片高度（像素）。 */
        private int height = 40;

        /**
         * 单个 IP 在 {@link RateLimit#getWindowSeconds()} 窗口内可签发的验证码张数。
         *
         * <p>比注册次数宽松得多：真人看不清会点着换几张，而每次签发都要画一张图并写一次 Redis。
         * 不限的话，这个免登接口本身就是一条廉价的 CPU 消耗路径。</p>
         */
        private int maxIssuePerWindow = 30;
    }

    /** 登录失败锁定参数。 */
    @Getter
    @Setter
    public static class LoginLock {

        /** 是否启用。对外部署强制启用。 */
        private boolean enabled = false;

        /** 窗口内允许的连续失败次数，达到即锁定。 */
        private int maxFailures = 5;

        /** 统计与锁定窗口（秒），默认 15 分钟。 */
        private int windowSeconds = 900;
    }
}
