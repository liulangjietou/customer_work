package com.richard.fyoung.customeradmin.auth.guard;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 登录拼图验证码参数。
 *
 * <p>登录拼图与注册图形验证码是两份独立凭据：前者保护密码与 LDAP 认证入口，
 * 后者保护匿名注册及邮件发送，不能共用开关、存储或错误码。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "admin.login-captcha")
public class LoginCaptchaProperties {

    /** challenge 有效期（秒）。 */
    @Positive
    private int challengeTtlSeconds = 120;

    /** 登录 proof 有效期（秒）。 */
    @Positive
    private int proofTtlSeconds = 120;

    /** 单个来源 IP 在窗口内最多签发的 challenge 数。 */
    @Positive
    private int maxIssuePerWindow = 30;

    /**
     * 单个来源 IP 在窗口内最多提交的拼图校验次数。
     *
     * <p>校验次数必须远小于 challenge 签发数：刷新题目只消耗图片签发额度，
     * 真正提交落点才消耗这里的猜测预算。</p>
     */
    @Positive
    private int maxVerifyPerWindow = 3;

    /** 单个来源 IP 在窗口内最多尝试消费的登录 proof 次数。 */
    @Positive
    private int maxProofConsumePerWindow = 120;

    /** challenge 签发、轨迹校验和 proof 消费共用的限流窗口（秒）。 */
    @Positive
    private int rateLimitWindowSeconds = 3600;

    /** 进程内模式下 challenge 与 proof 各自允许驻留的最大条数。 */
    @Positive
    private int maxInMemoryEntries = 10_000;

    /** 参与指纹计算的 User-Agent 最大字符数。 */
    @Positive
    private int maxUserAgentLength = 512;

    /**
     * 验证预算窗口不得短于 challenge 生命周期，否则攻击者可预签发题目并跨窗口叠加猜测次数。
     */
    @AssertTrue(message = "rate-limit-window-seconds 不能小于 challenge-ttl-seconds")
    public boolean isRateLimitWindowCoveringChallengeLifetime() {
        return rateLimitWindowSeconds >= challengeTtlSeconds;
    }
}
