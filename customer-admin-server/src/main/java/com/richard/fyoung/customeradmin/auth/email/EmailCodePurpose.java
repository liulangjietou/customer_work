package com.richard.fyoung.customeradmin.auth.email;

/**
 * 邮箱验证码的用途。
 *
 * <p><b>为什么必须分</b>：验证码按收件人存储，注册与找回密码共用一个键空间意味着两者可以互相顶替——
 * 拿一封注册验证码就能重置同一邮箱下账号的密码，反之亦然。用途进存储键之后，两条链路的码
 * 各在各的空间里，互不可见。</p>
 *
 * <p><b>而限流键刻意不分用途</b>（见 {@code EmailVerificationService}）：那几道限制保护的是
 * "同一个收件人邮箱不被轰炸"与"服务端不被刷"，跟发的是哪种码无关。按用途分开只会让攻击者
 * 交替调用两个接口，把对同一受害者的发信频率翻倍。</p>
 * @author owlzhangfq@gmail.com
 */
public enum EmailCodePurpose {

    /** 自助注册：证明申请人确实控制着他填的那个邮箱。 */
    REGISTER("register", "注册验证码", "您正在注册 %s 账号。",
        "请回到注册页面填写该验证码。若非本人操作，请忽略本邮件。"),

    /** 找回密码：证明申请人确实控制着账号上登记的那个邮箱。 */
    PASSWORD_RESET("password-reset", "密码重置验证码", "您正在重置 %s 账号的登录密码。",
        "请回到找回密码页面填写该验证码。若非本人操作，说明有人在尝试重置您的密码，"
            + "请忽略本邮件并考虑更换一个更强的密码。");

    /** 存储键片段，与邮箱一起构成验证码的存储键。 */
    private final String storageKey;

    /** 邮件主题（不含平台名前缀，前缀由 {@code AdminMailSender} 统一加）。 */
    private final String mailSubject;

    /** 正文首句模板，{@code %s} 处填平台名。 */
    private final String introTemplate;

    /** 正文末句：告诉用户拿这串码回哪里去填。 */
    private final String guidance;

    EmailCodePurpose(String storageKey, String mailSubject, String introTemplate, String guidance) {
        this.storageKey = storageKey;
        this.mailSubject = mailSubject;
        this.introTemplate = introTemplate;
        this.guidance = guidance;
    }

    public String storageKey() {
        return storageKey;
    }

    public String mailSubject() {
        return mailSubject;
    }

    public String intro(String platformName) {
        return String.format(introTemplate, platformName);
    }

    public String guidance() {
        return guidance;
    }
}
