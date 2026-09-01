package com.richard.fyoung.customeradmin.notify;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 邮件发送器装配。
 *
 * <p>邮箱验证码是自助注册的硬前提，配不通 SMTP 就等于注册整体不可用，
 * 所以这几项连接参数必须真的落到 JavaMail 属性上——它们配错时的表现是一次超时或一句
 * 含混的握手失败，光看日志很难回推到是哪一项。</p>
 * @author owlzhangfq@gmail.com
 */
class MailNotificationConfigTest {

    private static final String PROP_AUTH = "mail.smtp.auth";
    private static final String PROP_STARTTLS = "mail.smtp.starttls.enable";
    private static final String PROP_SSL_ENABLE = "mail.smtp.ssl.enable";

    private final MailNotificationConfig config = new MailNotificationConfig();

    /**
     * 空账号不能开 auth。
     *
     * <p>yml 的 {@code ${ADMIN_MAIL_USERNAME:}} 在未设环境变量时解析成<b>空串而非 null</b>，
     * 按 {@code != null} 判定会开着 auth 去做一次注定失败的空账号认证——
     * 而内网 SMTP 中继本就是不需要认证的合法配置。</p>
     */
    @Test
    void javaMailSender_shouldNotEnableAuthForBlankUsername() {
        JavaMailSenderImpl sender = (JavaMailSenderImpl) config.adminJavaMailSender(
            propertiesWith("smtp.internal", 25, ""));

        assertEquals("false", sender.getJavaMailProperties().getProperty(PROP_AUTH));
    }

    @Test
    void javaMailSender_shouldEnableAuthWhenUsernameConfigured() {
        JavaMailSenderImpl sender = (JavaMailSenderImpl) config.adminJavaMailSender(
            propertiesWith("smtp.qq.com", 587, "noreply@example.com"));

        assertEquals("true", sender.getJavaMailProperties().getProperty(PROP_AUTH));
    }

    /**
     * 465 的隐式 SSL 与 587 的 STARTTLS 是两套开关，必须各自透传。
     *
     * <p>国内多数邮箱服务商用 465；把它当 587 配（STARTTLS 开、SSL 关）会在握手阶段失败，
     * 用户看到的只是"验证码发送失败"。</p>
     */
    @Test
    void javaMailSender_shouldCarryImplicitSslSwitchForPort465() {
        MailNotificationProperties properties = propertiesWith("smtp.qq.com", 465, "noreply@example.com");
        properties.setSslEnabled(true);
        properties.setStartTlsEnabled(false);

        JavaMailSenderImpl sender = (JavaMailSenderImpl) config.adminJavaMailSender(properties);

        assertEquals("true", sender.getJavaMailProperties().getProperty(PROP_SSL_ENABLE));
        assertEquals("false", sender.getJavaMailProperties().getProperty(PROP_STARTTLS));
        assertEquals(465, sender.getPort());
    }

    /** 缺 host 时不创建发送器：创建了只会让每次发信都去连一个 null 主机，表现为整段超时。 */
    @Test
    void javaMailSender_shouldNotBeCreatedWithoutHost() {
        JavaMailSender sender = config.adminJavaMailSender(propertiesWith("  ", 587, "noreply@example.com"));

        assertNull(sender);
    }

    private MailNotificationProperties propertiesWith(String host, int port, String username) {
        MailNotificationProperties properties = new MailNotificationProperties();
        properties.setEnabled(true);
        properties.setHost(host);
        properties.setPort(port);
        properties.setUsername(username);
        return properties;
    }
}
