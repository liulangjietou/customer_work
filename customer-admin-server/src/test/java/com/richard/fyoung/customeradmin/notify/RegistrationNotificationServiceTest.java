package com.richard.fyoung.customeradmin.notify;

import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

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
    private AdminMailSender mailSender;
    private RegistrationNotificationService service;

    @BeforeEach
    void setUp() {
        siteMessageService = mock(SiteMessageService.class);
        mailSender = mock(AdminMailSender.class);
        service = new RegistrationNotificationService(siteMessageService, mailSender);
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
    void notifyApprovalResult_shouldSendMailWhenAvailable() {
        when(mailSender.available()).thenReturn(true);
        when(mailSender.loginUrl()).thenReturn("https://console.example.com");

        service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp");

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(toCaptor.capture(), any(), textCaptor.capture());
        assertEquals("richard@example.com", toCaptor.getValue());
        assertTrue(textCaptor.getValue().contains("https://console.example.com"));
    }

    /** 没有邮箱就没法发——LDAP 影子账号与管理员预建账号都是这种情况。 */
    @Test
    void notifyApprovalResult_shouldSkipMailWithoutEmailAddress() {
        when(mailSender.available()).thenReturn(true);
        SysUser user = user();
        user.setEmail(null);

        service.notifyApprovalResult(user, UserApprovalStatus.APPROVED, null, "acme-corp");

        verify(mailSender, never()).send(any(), any(), any());
        verify(siteMessageService).send(any(), any(), any(), any(), any(), any());
    }

    /** 邮件未配置（available=false）时静默跳过，不抛。 */
    @Test
    void notifyApprovalResult_shouldSkipMailWhenUnavailable() {
        service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp");

        verify(mailSender, never()).send(any(), any(), any());
        verify(siteMessageService).send(any(), any(), any(), any(), any(), any());
    }

    /** SMTP 挂了不能把审核带下水。 */
    @Test
    void notifyApprovalResult_shouldSwallowMailFailure() {
        when(mailSender.available()).thenReturn(true);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(), any(), any());

        assertDoesNotThrow(() ->
            service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp"));

        verify(siteMessageService).send(any(), any(), any(), any(), any(), any());
    }

    /** 站内信写失败同样不能中断审核，且不该阻止邮件继续发出。 */
    @Test
    void notifyApprovalResult_shouldSwallowSiteMessageFailureAndStillSendMail() {
        when(mailSender.available()).thenReturn(true);
        doThrow(new IllegalStateException("db down"))
            .when(siteMessageService).send(any(), any(), any(), any(), any(), any());

        assertDoesNotThrow(() ->
            service.notifyApprovalResult(user(), UserApprovalStatus.APPROVED, null, "acme-corp"));

        verify(mailSender).send(any(), any(), any());
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("richard");
        user.setEmail("richard@example.com");
        return user;
    }

}
