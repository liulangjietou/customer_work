package com.richard.fyoung.customeradmin.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * 后台唯一的邮件出口。
 *
 * <p>审核结果通知与注册验证码都要发信，各写一份"取 sender、拼发件人、组装 SimpleMailMessage"
 * 的代码就会出现两套发件人口径——改了一处忘了另一处，而两边都不会报错。</p>
 *
 * <p><b>发送失败一律向上抛</b>，由调用方决定吞不吞：审核通知是旁路（吞掉记日志即可），
 * 而注册验证码发不出去必须让用户当场看到失败——否则他会一直等一封永远不会到的邮件。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class AdminMailSender {

    private final MailNotificationProperties properties;
    private final ObjectProvider<JavaMailSender> senderProvider;

    public AdminMailSender(MailNotificationProperties properties,
                           ObjectProvider<JavaMailSender> senderProvider) {
        this.properties = properties;
        this.senderProvider = senderProvider;
    }

    /**
     * 当前是否真的能发信。
     *
     * <p>"配置开了"与"能发"不是一回事：{@code MailNotificationConfig} 在 host 为空时
     * 不会创建 sender。依赖发信的功能（注册验证码）必须先问这一句再决定是否放行。</p>
     */
    public boolean available() {
        return properties.isEnabled() && senderProvider.getIfAvailable() != null;
    }

    /**
     * 发一封纯文本邮件。
     *
     * @throws IllegalStateException 邮件未启用或没有可用的发送器
     */
    public void send(String to, String subject, String text) {
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (!properties.isEnabled() || sender == null) {
            throw new IllegalStateException("mail is not available");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(resolveFrom());
        message.setTo(to);
        message.setSubject("【" + properties.getPlatformName() + "】" + subject);
        message.setText(text);
        sender.send(message);
    }

    /** 发件人：显式配置优先，留空时退回 SMTP 账号本身。 */
    private String resolveFrom() {
        return StringUtils.hasText(properties.getFrom())
            ? properties.getFrom() : properties.getUsername();
    }

    /** 平台名，供邮件正文引用，避免调用方再注入一次属性类。 */
    public String platformName() {
        return properties.getPlatformName();
    }

    /** 登录地址，可能为空。 */
    public String loginUrl() {
        return properties.getLoginUrl();
    }
}
