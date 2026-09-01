package com.richard.fyoung.customeradmin.auth.guard;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.List;

/**
 * 自助注册与登录的防滥用参数。
 *
 * <p><b>邮箱必填与邮箱验证码不在这里配</b>：注册必须填邮箱、必须收到并填回验证码，
 * 是本系统的不变式而不是部署选项（{@code RegistrationGuard#admit} 无条件核验）。
 * 留一个能配成 false 的开关，
 * 等于把"注册者是否真的拥有那个邮箱"变成一件可以漏配的事——而漏配不报错，
 * 只在某天审核结果发到别人邮箱时才被发现。</p>
 *
 * <p>其余几项内网实例可以整体关掉（默认即关），对外开放实例由
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

    @NestedConfigurationProperty
    private RateLimit rateLimit = new RateLimit();

    @NestedConfigurationProperty
    private Captcha captcha = new Captcha();

    @NestedConfigurationProperty
    private LoginLock loginLock = new LoginLock();

    @NestedConfigurationProperty
    private EmailVerification emailVerification = new EmailVerification();

    /**
     * 是否信任反向代理写入的 {@code X-Forwarded-For}。
     *
     * <p>默认不信任，避免直连暴露或错误代理配置时客户端伪造来源地址绕开限流。
     * 显式开启后仍只有 {@link #trustedProxyCidrs} 命中的直接连接方可以写入转发链，
     * 服务端会从右向左剥离可信代理，而不是直接采信客户端可控的最左段。</p>
     */
    private boolean trustForwardedHeader = false;

    /**
     * 允许写入转发头的直接代理网段。开启转发头信任时至少配置一项，支持 IPv4/IPv6 CIDR。
     * 空列表代表任何连接方都不可信。
     */
    private List<String> trustedProxyCidrs = List.of();

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

    /**
     * 邮箱验证码参数。
     *
     * <p>注册必须先向邮箱收一封验证码、核验通过才创建账号——这是"这个邮箱确实归申请人所有"
     * 的唯一证据，<b>没有关闭开关</b>。代价是 SMTP 必须真的可用，否则整条自助注册链路
     * 在"获取验证码"那一步就走不下去；prod profile 由
     * {@code AdminProductionReadinessValidator} 在启动时就拒绝这种配置，
     * 而不是等用户站在注册页上才发现。</p>
     *
     * <p>下面几项只调参数，不回答"要不要验"。</p>
     */
    @Getter
    @Setter
    public static class EmailVerification {

        /** 验证码位数。 */
        private int codeLength = 6;

        /**
         * 有效期（秒），默认 10 分钟。
         *
         * <p>比图形验证码长得多：邮件从投递到被看见本身就要几十秒到几分钟，
         * 按图形码那样给 3 分钟会让相当一部分人拿到手时已经过期。</p>
         */
        private int ttlSeconds = 600;

        /**
         * 同一份验证码允许核验失败的次数，达到即作废。
         *
         * <p>不设成"错一次就作废"：那会逼着用户为一个笔误重新收信，而每一次重发
         * 都是一封真实的外部邮件。也不能不限——6 位数字在不限次数下是可以直接猜穿的。</p>
         */
        private int maxAttempts = 5;

        /** 同一邮箱两次发码之间的最小间隔（秒）。 */
        private int resendCooldownSeconds = 60;

        /**
         * 同一邮箱每天可收到的验证码封数。
         *
         * <p>拦的是"拿别人的邮箱当轰炸目标"——冷却只能限制频率，限制不了总量。</p>
         */
        private int maxSendPerEmailPerDay = 10;

        /** 同一来源 IP 在 {@link RateLimit#getWindowSeconds()} 窗口内可触发的发信次数。 */
        private int maxSendPerIpPerWindow = 10;
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
