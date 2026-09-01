package com.richard.fyoung.customeradmin.auth.email;

import com.richard.fyoung.customeradmin.auth.guard.RegistrationGuardProperties;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.notify.AdminMailSender;
import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 邮箱验证码：发码与核验。注册与找回密码共用这一套。
 *
 * <p>发码是<b>唯一一个会向站外第三方产生副作用的匿名接口</b>——它让服务端给任意地址发一封信。
 * 因此这里的防护比注册本身还密：图形验证码（在 Controller 那一层）、来源 IP 限流、
 * 同一邮箱的重发冷却、同一邮箱的每日总量。四道各挡一种滥用：</p>
 * <ul>
 *   <li>图形码挡无人值守的脚本；</li>
 *   <li>IP 限流挡一个来源换着邮箱轰炸；</li>
 *   <li>冷却挡对同一个受害者的高频轰炸；</li>
 *   <li>日总量挡"每 60 秒一封、发一整天"——冷却对这种打法完全无效。</li>
 * </ul>
 *
 * <p><b>验证码按用途分键，限流键刻意不分</b>：分键是为了让注册码不能拿去重置密码
 * （见 {@link EmailCodePurpose}）；而后三道限流保护的是"同一个收件人邮箱不被轰炸"与
 * "服务端不被刷"，与发的是哪种码无关——按用途各给一份额度，等于让攻击者交替调用两个接口
 * 就把对同一受害者的发信量翻倍。</p>
 *
 * <p><b>{@link #reserveSendQuota} 与 {@link #issueAndSend} 是拆开的</b>：找回密码那条链路
 * 在"邮箱压根没注册"时同样要消耗额度却不能发信——否则"有没有被限流"本身就成了
 * 账号存在性探针，含糊的响应文案也就白写了。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class EmailVerificationService {

    private static final String COOLDOWN_KEY_PREFIX = "admin:email-code:cooldown:";
    private static final String DAILY_KEY_PREFIX = "admin:email-code:daily:";
    private static final String IP_KEY_PREFIX = "admin:email-code:ip:";
    private static final int ONE_DAY_SECONDS = 86400;
    private static final String DIGITS = "0123456789";

    private final RegistrationGuardProperties properties;
    private final EmailVerificationStore store;
    private final AdminMailSender mailSender;
    private final WindowCounter counter;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(RegistrationGuardProperties properties,
                                    EmailVerificationStore store,
                                    AdminMailSender mailSender,
                                    WindowCounter counter) {
        this.properties = properties;
        this.store = store;
        this.mailSender = mailSender;
        this.counter = counter;
    }

    /**
     * 向指定邮箱发送一封验证码。
     *
     * <p>四道限制按"代价从低到高"排：冷却与日限只查计数器，发信才是真正贵的那一步。
     * 只有全部通过才生成验证码——先生成再判定的话，被拒的请求也会把上一份还能用的
     * 验证码覆盖掉，用户手里那封信就莫名其妙失效了。</p>
     *
     * @param purpose  验证码用途，决定存储键与邮件文案
     * @param email    收件邮箱（调用方已归一为小写）
     * @param clientIp 来源 IP
     * @return 验证码有效期（秒），供前端提示
     */
    public int sendCode(EmailCodePurpose purpose, String email, String clientIp) {
        reserveSendQuota(email, clientIp);
        issueAndSend(purpose, email);
        return properties.getEmailVerification().getTtlSeconds();
    }

    /** 验证码有效期（秒）：找回密码那条链路即使不发信也要返回它，让两种响应完全一致。 */
    public int codeTtlSeconds() {
        return properties.getEmailVerification().getTtlSeconds();
    }

    /**
     * 预扣一次发信额度：可用性 + 冷却 + 日总量 + 来源 IP，四道全过才返回。
     *
     * <p>单独暴露是给找回密码用的——那条链路必须在"这个用户名和邮箱到底对不对得上"之前
     * 就把额度扣掉，让存在与不存在的两种请求在限流上完全一致。</p>
     *
     * @throws BizException 邮件不可用或触及任一道限制
     */
    public void reserveSendQuota(String email, String clientIp) {
        RegistrationGuardProperties.EmailVerification config = properties.getEmailVerification();
        if (!mailSender.available()) {
            // 发不出去就别让用户干等一封永远不会到的信
            log.error("email verification requested but mail is unavailable, code={}",
                "AUTH-EMAIL-CODE-NO-MAIL");
            throw new BizException(ResultCode.EMAIL_CODE_SEND_FAILED,
                "邮件服务未配置，请联系管理员");
        }
        checkCooldown(email, config);
        checkDailyQuota(email, config);
        checkIpQuota(clientIp, config);
    }

    /**
     * 生成验证码、落存储并真的发出去。额度必须已由 {@link #reserveSendQuota} 预扣。
     *
     * @throws BizException 发信失败（此时刚写入的码已被清掉，不留死码）
     */
    public void issueAndSend(EmailCodePurpose purpose, String email) {
        RegistrationGuardProperties.EmailVerification config = properties.getEmailVerification();
        String code = randomCode(config.getCodeLength());
        long expireAtMs = System.currentTimeMillis() + config.getTtlSeconds() * 1000L;
        store.save(purpose, email, new EmailVerificationCode(code, 0, expireAtMs));
        try {
            mailSender.send(email, purpose.mailSubject(),
                buildMailText(purpose, code, config.getTtlSeconds()));
        } catch (Exception e) {
            // 信没发出去，手里那份验证码就是死码，留着只会让下一次发码被冷却挡住
            store.invalidate(purpose, email);
            log.error("email verification code send failed, code={}, purpose={}",
                "AUTH-EMAIL-CODE-SEND-FAIL", purpose, e);
            throw new BizException(ResultCode.EMAIL_CODE_SEND_FAILED);
        }
        log.info("email verification code sent, purpose={}, ttlSeconds={}", purpose, config.getTtlSeconds());
    }

    /**
     * 核验验证码。
     *
     * <p>通过即销毁（同一份码不能注册两个账号）；失败累加次数，达到上限一并销毁——
     * 6 位数字在不限次数下是可以直接猜穿的。</p>
     */
    public void verify(EmailCodePurpose purpose, String email, String input) {
        if (!StringUtils.hasText(input)) {
            throw new BizException(ResultCode.EMAIL_CODE_INVALID, "请输入邮箱验证码");
        }
        EmailVerificationCode stored = store.get(purpose, email);
        if (stored == null) {
            throw new BizException(ResultCode.EMAIL_CODE_REISSUE_REQUIRED);
        }
        if (!stored.code().equals(input.trim())) {
            int maxAttempts = properties.getEmailVerification().getMaxAttempts();
            EmailVerificationCode failed = stored.withOneMoreFailure();
            if (failed.attempts() >= maxAttempts) {
                store.invalidate(purpose, email);
                log.info("email verification code invalidated after {} failed attempts, purpose={}",
                    maxAttempts, purpose);
                throw new BizException(ResultCode.EMAIL_CODE_REISSUE_REQUIRED);
            }
            store.save(purpose, email, failed);
            throw new BizException(ResultCode.EMAIL_CODE_INVALID);
        }
        store.invalidate(purpose, email);
    }

    /** 同一邮箱的重发冷却：窗口内只放行一次。 */
    private void checkCooldown(String email, RegistrationGuardProperties.EmailVerification config) {
        if (config.getResendCooldownSeconds() <= 0) {
            return;
        }
        if (!counter.tryAcquireSliding(COOLDOWN_KEY_PREFIX + email, 1, config.getResendCooldownSeconds())) {
            throw new BizException(ResultCode.EMAIL_CODE_TOO_FREQUENT,
                "验证码已发送，请 " + config.getResendCooldownSeconds() + " 秒后再试");
        }
    }

    /** 同一邮箱的每日总量：冷却限制不了"每 60 秒一封发一整天"这种轰炸。 */
    private void checkDailyQuota(String email, RegistrationGuardProperties.EmailVerification config) {
        if (config.getMaxSendPerEmailPerDay() <= 0) {
            return;
        }
        if (!counter.tryAcquireSliding(DAILY_KEY_PREFIX + email,
            config.getMaxSendPerEmailPerDay(), ONE_DAY_SECONDS)) {
            log.info("email verification blocked by per-email daily quota, quota={}",
                config.getMaxSendPerEmailPerDay());
            throw new BizException(ResultCode.EMAIL_CODE_TOO_FREQUENT,
                "该邮箱今日获取验证码次数过多，请明天再试");
        }
    }

    /** 来源 IP 限流：挡一个来源换着邮箱轰炸。 */
    private void checkIpQuota(String clientIp, RegistrationGuardProperties.EmailVerification config) {
        if (config.getMaxSendPerIpPerWindow() <= 0) {
            return;
        }
        if (!counter.tryAcquireSliding(IP_KEY_PREFIX + clientIp,
            config.getMaxSendPerIpPerWindow(), properties.getRateLimit().getWindowSeconds())) {
            log.info("email verification blocked by ip quota, ip={}, quota={}",
                clientIp, config.getMaxSendPerIpPerWindow());
            throw new BizException(ResultCode.EMAIL_CODE_TOO_FREQUENT);
        }
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }

    /**
     * 邮件正文。
     *
     * <p>刻意不带任何链接：验证码邮件是钓鱼最爱模仿的形态，正文里出现可点的链接
     * 会把"别点邮件里的链接"这条常识教反。找回密码那封尤其如此——"重置密码链接"
     * 正是钓鱼邮件最常用的幌子。用户回到自己打开的那个页面填码即可。</p>
     */
    private String buildMailText(EmailCodePurpose purpose, String code, int ttlSeconds) {
        return purpose.intro(mailSender.platformName()) + "\n\n"
            + "验证码：" + code + "\n"
            + "有效期：" + (ttlSeconds / 60) + " 分钟\n\n"
            + purpose.guidance();
    }

    /** 邮箱归一：大小写不同的同一邮箱是同一个人，键与唯一约束都必须能挡住。 */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
