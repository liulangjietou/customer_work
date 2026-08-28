package com.richard.fyoung.customeradmin.auth.email;

/**
 * 注册邮箱验证码的短期存储 SPI。
 *
 * <p>与图形验证码那套刻意分开：图形码按一次性凭据存（{@code captchaId} → 答案，取出即失效），
 * 邮箱码按<b>收件人</b>存，且必须允许有限次重试——用户输错一位就作废、要求重新收信，
 * 只会把人赶走，而每一次重发都是一封真实的外部邮件。</p>
 * @author owlzhangfq@gmail.com
 */
public interface EmailVerificationStore {

    /** 写入（或覆盖）某个邮箱的验证码。 */
    void save(String email, EmailVerificationCode code);

    /** 读取但不删除；不存在或已过期返回 {@code null}。 */
    EmailVerificationCode get(String email);

    /** 删除该邮箱的验证码（核验通过、或失败次数耗尽）。 */
    void invalidate(String email);
}
