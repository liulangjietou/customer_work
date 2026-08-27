package com.richard.fyoung.customeradmin.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台唯一的邮件出口。
 *
 * <p>{@link AdminMailSender#available()} 是"能不能发"的唯一判据——配置开着但 host 为空时
 * {@code MailNotificationConfig} 不会创建 sender，此时 enabled 为 true 却发不出去。
 * 依赖发信的功能（注册验证码）必须先问这一句，否则用户会一直等一封永远不会到的邮件。</p>
 */
class AdminMailSenderTest {

    private MailNotificationProperties properties;
    private JavaMailSender javaMailSender;

    @BeforeEach
    void setUp() {
        properties = new MailNotificationProperties();
        properties.setUsername("smtp-user@example.com");
        javaMailSender = mock(JavaMailSender.class);
    }

    @Test
    void available_shouldRequireBothEnabledFlagAndSender() {
        assertFalse(sender(javaMailSender).available(), "未启用时不可用");

        properties.setEnabled(true);
        assertTrue(sender(javaMailSender).available());
        assertFalse(sender(null).available(), "启用但没有 sender（host 未配）时同样不可用");
    }

    @Test
    void send_shouldPrefixSubjectWithPlatformName() {
        properties.setEnabled(true);
        properties.setPlatformName("客服智能体平台");

        sender(javaMailSender).send("richard@example.com", "注册验证码", "正文");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        assertEquals("【客服智能体平台】注册验证码", captor.getValue().getSubject());
        assertEquals("richard@example.com", captor.getValue().getTo()[0]);
        assertEquals("正文", captor.getValue().getText());
    }

    /** 发件人显式配置优先，留空时退回 SMTP 账号本身——两处各写一遍就会出现口径分叉。 */
    @Test
    void send_shouldFallBackToSmtpUsernameAsSenderAddress() {
        properties.setEnabled(true);

        sender(javaMailSender).send("richard@example.com", "标题", "正文");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        assertEquals("smtp-user@example.com", captor.getValue().getFrom());

        properties.setFrom("noreply@example.com");
        JavaMailSender another = mock(JavaMailSender.class);
        sender(another).send("richard@example.com", "标题", "正文");
        ArgumentCaptor<SimpleMailMessage> second = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(another).send(second.capture());
        assertEquals("noreply@example.com", second.getValue().getFrom());
    }

    /** 发不出去一律抛，吞不吞由调用方按语义决定：审核通知吞，注册验证码不能吞。 */
    @Test
    void send_shouldThrowWhenUnavailable() {
        assertThrows(IllegalStateException.class,
            () -> sender(javaMailSender).send("richard@example.com", "标题", "正文"));

        properties.setEnabled(true);
        assertThrows(IllegalStateException.class,
            () -> sender(null).send("richard@example.com", "标题", "正文"));
        verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
    }

    @SuppressWarnings("unchecked")
    private AdminMailSender sender(JavaMailSender javaMail) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(javaMail);
        return new AdminMailSender(properties, provider);
    }
}
