package com.richard.fyoung.customeradmin.notify;

import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册审核结果通知。
 *
 * <p>这里最要紧的一条不是"通知发得对"，而是<b>通知失败绝不影响审核结果</b>：
 * 审核是一次事务性的权限变更，让一次 SMTP 超时把已经算通过的审核打回，
 * 只会造成"审核人以为失败、又点了一次"的重复操作。</p>
 */
class RegistrationNotificationServiceTest {

    private SiteMessageService siteMessageService;
    private MailNotificationProperties mailProperties;
    private JavaMailSender mailSender;
    private RegistrationNotificationService service;

    @BeforeEach
    void setUp() {
        siteMessageService = mock(SiteMessageService.class);
        mailProperties = new MailNotificationProperties();
        mailSender = mock(JavaMailSender.class);
        service = new RegistrationNotificationService(siteMessageService, mailProperties,
            providerOf(mailSender));
    }

    @Test
    void notifyApprovalResult_shouldWriteSiteMessageWithTenantOnApproval() {
        service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, "资料齐全", "acme-corp");

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(siteMessageService).send(org.mockito.ArgumentMatchers.eq(9L), titleCaptor.capture(),
            contentCaptor.capture(), org.mockito.ArgumentMatchers.eq("REGISTER_APPROVAL"),
            org.mockito.ArgumentMatchers.eq("9"), org.mockito.ArgumentMatchers.isNull());
        assertEquals("账号审核已通过", titleCaptor.getValue());
        assertTrue(contentCaptor.getValue().contains("acme-corp"));
        assertTrue(contentCaptor.getValue().contains("资料齐全"));
    }

    @Test
    void notifyApprovalResult_shouldWriteRejectionReason() {
        service.notifyApprovalResult(user(), UserApprovalStatus.REJECTED, "资料不完整", null);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(siteMessageService).send(any(), any(), contentCaptor.capture(), any(), any(), any());
        assertTrue(contentCaptor.getValue().contains("未通过审核"));
        assertTrue(contentCaptor.getValue().contains("资料不完整"));
    }

    @Test
    void notifyApprovalResult_shouldSendMailWhenEnabled() {
        mailProperties.setEnabled(true);
        mailProperties.setFrom("noreply@example.com");
        mailProperties.setLoginUrl("https://console.example.com");

        service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertEquals("noreply@example.com", message.getFrom());
        assertEquals("richard@example.com", message.getTo()[0]);
        assertTrue(message.getText().contains("https://console.example.com"));
    }

    /** 没有邮箱就没法发——LDAP 影子账号与管理员预建账号都是这种情况。 */
    @Test
    void notifyApprovalResult_shouldSkipMailWithoutEmailAddress() {
        mailProperties.setEnabled(true);
        SysUser user = user();
        user.setEmail(null);

        service.notifyApprovalResult(user, UserApprovalStatus.APPROVED, null, "acme-corp");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(siteMessageService).send(any(), any(), any(), any(), any(), any());
    }

    @Test
    void notifyApprovalResult_shouldNotSendMailWhenDisabled() {
        service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    /** SMTP 挂了不能把审核带下水。 */
    @Test
    void notifyApprovalResult_shouldSwallowMailFailure() {
        mailProperties.setEnabled(true);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() ->
            service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp"));

        verify(siteMessageService).send(any(), any(), any(), any(), any(), any());
    }

    /** 站内信写失败同样不能中断审核，且不该阻止邮件继续发出。 */
    @Test
    void notifyApprovalResult_shouldSwallowSiteMessageFailureAndStillSendMail() {
        mailProperties.setEnabled(true);
        doThrow(new IllegalStateException("db down"))
            .when(siteMessageService).send(any(), any(), any(), any(), any(), any());

        assertDoesNotThrow(() ->
            service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp"));

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    /** 启用了却没有可用的发送器（host 未配）时静默跳过，不抛。 */
    @Test
    void notifyApprovalResult_shouldTolerateMissingMailSender() {
        mailProperties.setEnabled(true);
        service = new RegistrationNotificationService(siteMessageService, mailProperties,
            providerOf(null));

        assertDoesNotThrow(() ->
            service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp"));
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("richard");
        user.setEmail("richard@example.com");
        return user;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> providerOf(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }
}
