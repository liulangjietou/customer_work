package com.richard.fyoung.customeradmin.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * 邮件发送装配。
 *
 * <p>本模块 {@code spring.autoconfigure.exclude} 了不少自动装配，且 SMTP 参数走
 * {@code admin.notification.mail.*} 而不是 Spring 的 {@code spring.mail.*}——
 * admin 的外部依赖一律收在 {@code admin.} 前缀下，便于一眼看清这个服务连了哪些外部系统。
 * 因此这里显式 new 一个 {@link JavaMailSenderImpl}，不依赖 Boot 的 MailSenderAutoConfiguration。</p>
 *
 * <p>{@code @EnableConfigurationProperties} 挂在外层无条件类上：即使邮件关闭，
 * {@link RegistrationNotificationService} 仍要读它判断"要不要发"。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MailNotificationProperties.class)
public class MailNotificationConfig {

    private static final String PROP_AUTH = "mail.smtp.auth";
    private static final String PROP_STARTTLS = "mail.smtp.starttls.enable";
    private static final String PROP_SSL_ENABLE = "mail.smtp.ssl.enable";
    private static final String PROP_CONNECT_TIMEOUT = "mail.smtp.connectiontimeout";
    private static final String PROP_TIMEOUT = "mail.smtp.timeout";
    private static final String PROP_WRITE_TIMEOUT = "mail.smtp.writetimeout";

    /**
     * 后台唯一的邮件出口，审核通知与注册验证码共用。
     *
     * <p>无条件创建：邮件关闭时它也要存在，好让依赖发信的功能能问一句
     * {@link AdminMailSender#available()} 再决定放不放行，而不是各自去读属性。</p>
     */
    @Bean
    public AdminMailSender adminMailSender(MailNotificationProperties properties,
                                           ObjectProvider<JavaMailSender> senderProvider) {
        return new AdminMailSender(properties, senderProvider);
    }

    /**
     * 仅在启用且配了 host 时创建。缺 host 却创建，会让每次通知都去连 {@code null} 主机，
     * 表现为审核接口卡住到超时——而通知本该是旁路。
     */
    @Bean
    @ConditionalOnProperty(prefix = "admin.notification.mail", name = "enabled", havingValue = "true")
    public JavaMailSender adminJavaMailSender(MailNotificationProperties properties) {
        if (properties.getHost() == null || properties.getHost().isBlank()) {
            log.error("mail notification enabled but host is blank, code={}", "NOTIFY-MAIL-NO-HOST");
            return null;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties mailProps = sender.getJavaMailProperties();
        // 空串不算"配了账号"：yml 的 ${ADMIN_MAIL_USERNAME:} 在未设环境变量时解析成空串而非 null，
        // 按 != null 判定会开着 auth 去做一次注定失败的空账号认证，报错还指向别处
        mailProps.put(PROP_AUTH, String.valueOf(StringUtils.hasText(properties.getUsername())));
        mailProps.put(PROP_STARTTLS, String.valueOf(properties.isStartTlsEnabled()));
        mailProps.put(PROP_SSL_ENABLE, String.valueOf(properties.isSslEnabled()));
        String timeout = String.valueOf(properties.getTimeoutMs());
        mailProps.put(PROP_CONNECT_TIMEOUT, timeout);
        mailProps.put(PROP_TIMEOUT, timeout);
        mailProps.put(PROP_WRITE_TIMEOUT, timeout);
        log.info("admin mail sender configured, host={}, port={}", properties.getHost(), properties.getPort());
        return sender;
    }
}
