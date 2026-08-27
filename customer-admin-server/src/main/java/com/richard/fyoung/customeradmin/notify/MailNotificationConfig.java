package com.richard.fyoung.customeradmin.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

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
        mailProps.put(PROP_AUTH, String.valueOf(properties.getUsername() != null));
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
