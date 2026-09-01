package com.richard.fyoung.customeradmin.auth.email;

/**
 * 邮箱验证码的短期存储 SPI。
 *
 * <p>与图形验证码那套刻意分开：图形码按一次性凭据存（{@code captchaId} → 答案，取出即失效），
 * 邮箱码按<b>收件人</b>存，且必须允许有限次重试——用户输错一位就作废、要求重新收信，
 * 只会把人赶走，而每一次重发都是一封真实的外部邮件。</p>
 *
 * <p><b>键由用途与收件人两段共同构成</b>：注册与找回密码各有各的空间，
 * 一封注册验证码不能拿去重置密码（见 {@link EmailCodePurpose}）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface EmailVerificationStore {

    /** 写入（或覆盖）某个邮箱在该用途下的验证码。 */
    void save(EmailCodePurpose purpose, String email, EmailVerificationCode code);

    /** 读取但不删除；不存在或已过期返回 {@code null}。 */
    EmailVerificationCode get(EmailCodePurpose purpose, String email);

    /** 删除该邮箱在该用途下的验证码（核验通过、或失败次数耗尽）。 */
    void invalidate(EmailCodePurpose purpose, String email);
}
