package com.richard.fyoung.customeradmin.notify;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审核结果邮件通知的连接参数。
 *
 * <p>默认关闭。开着但没配 host 会让每次审核都尝试连一个不存在的 SMTP 并超时，
 * 而审核本身是同步接口——通知失败不该拖垮审核，见
 * {@code RegistrationNotificationService} 的异常处理。</p>
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "admin.notification.mail")
public class MailNotificationProperties {

    /** 是否启用邮件通知。关闭时只写站内信。 */
    private boolean enabled = false;

    /** SMTP 主机。 */
    private String host;

    /** SMTP 端口，默认 587（STARTTLS）。 */
    private int port = 587;

    /** SMTP 账号。 */
    private String username;

    /** SMTP 密码或授权码。 */
    private String password;

    /** 发件人地址；留空时取 {@link #username}。 */
    private String from;

    /** 发件人显示名。 */
    private String fromName = "客服智能体平台";

    /** 是否启用 STARTTLS。465 端口的隐式 SSL 走 {@link #sslEnabled}。 */
    private boolean startTlsEnabled = true;

    /** 是否使用隐式 SSL（通常配 465 端口）。 */
    private boolean sslEnabled = false;

    /** 连接与读取超时（毫秒）。审核接口是同步的，超时必须短。 */
    private int timeoutMs = 5000;

    /** 平台名称，出现在邮件正文里。 */
    private String platformName = "客服智能体平台";

    /** 登录地址，写进邮件正文让用户直接点进来。 */
    private String loginUrl;
}
