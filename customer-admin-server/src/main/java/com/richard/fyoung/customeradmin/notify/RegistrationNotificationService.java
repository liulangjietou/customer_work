package com.richard.fyoung.customeradmin.notify;

import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 注册审核结果通知：站内信 + 邮件。
 *
 * <p><b>为什么两条都要</b>：站内信要登录后才看得到，而被拒绝的人根本没有可用的后台；
 * 待审核的人也不会天天登录刷新。只发站内信等于没通知。反过来邮件可能被拦、
 * 地址可能填错，站内信是审核结果的落地留痕。</p>
 *
 * <p><b>通知失败绝不影响审核结果</b>：审核是一次事务性的权限变更，通知是旁路。
 * 这里把所有异常吞在本方法内并记 error 日志——让一次 SMTP 超时把已经算通过的审核回滚，
 * 会造成"审核人以为失败、又点了一次"的重复操作。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class RegistrationNotificationService {

    /** 站内信业务类型，用于前端分类与后续检索。 */
    private static final String BIZ_TYPE = "REGISTER_APPROVAL";

    private final SiteMessageService siteMessageService;
    private final MailNotificationProperties mailProperties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public RegistrationNotificationService(SiteMessageService siteMessageService,
                                           MailNotificationProperties mailProperties,
                                           ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.siteMessageService = siteMessageService;
        this.mailProperties = mailProperties;
        this.mailSenderProvider = mailSenderProvider;
    }

    /**
     * 事务提交后发出审核结果通知。
     *
     * <p>必须等提交：审核事务里还会改租户归属、重写角色关系、递增认证版本，
     * 其中任何一步失败都会回滚，而邮件发出去就收不回来了。</p>
     *
     * @param user         被审核的用户（已写入新的审核状态与租户）
     * @param decision     审核结论
     * @param remark       审核说明或拒绝原因，可空
     * @param targetTenant 通过时的归属租户编码，拒绝时无意义
     */
    public void notifyApprovalResultAfterCommit(SysUser user, UserApprovalStatus decision,
                                                String remark, String targetTenant) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifyApprovalResult(user, decision, remark, targetTenant);
                }
            });
            return;
        }
        notifyApprovalResult(user, decision, remark, targetTenant);
    }

    /**
     * 立即发出通知。事务内调用会在回滚时发出"已通过"的假消息，
     * 因此审核链路必须走 {@link #notifyApprovalResultAfterCommit}。
     */
    public void notifyApprovalResult(SysUser user, UserApprovalStatus decision,
                                     String remark, String targetTenant) {
        boolean approved = decision == UserApprovalStatus.APPROVED;
        String title = approved ? "账号审核已通过" : "账号审核未通过";
        String content = buildContent(approved, remark, targetTenant);
        try {
            siteMessageService.send(user.getId(), title, content, BIZ_TYPE,
                String.valueOf(user.getId()), null);
        } catch (Exception e) {
            log.error("site message notify failed, code={}, userId={}",
                "NOTIFY-SITE-MESSAGE-FAIL", user.getId(), e);
        }
        sendMail(user, title, content);
    }

    private String buildContent(boolean approved, String remark, String targetTenant) {
        StringBuilder sb = new StringBuilder();
        if (approved) {
            sb.append("您的账号已通过审核");
            if (StringUtils.hasText(targetTenant)) {
                sb.append("，归属租户：").append(targetTenant);
            }
            sb.append("。重新登录后即可看到已开通的功能菜单。");
        } else {
            sb.append("很抱歉，您的账号未通过审核。");
        }
        if (StringUtils.hasText(remark)) {
            sb.append("\n审核说明：").append(remark);
        }
        if (StringUtils.hasText(mailProperties.getLoginUrl())) {
            sb.append("\n登录地址：").append(mailProperties.getLoginUrl());
        }
        return sb.toString();
    }

    /** 邮件是旁路：未启用、无地址、发送失败都只记日志，不向上抛。 */
    private void sendMail(SysUser user, String title, String content) {
        if (!mailProperties.isEnabled() || !StringUtils.hasText(user.getEmail())) {
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.error("mail notify skipped, no JavaMailSender available, code={}, userId={}",
                "NOTIFY-MAIL-NO-SENDER", user.getId());
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(resolveFrom());
            message.setTo(user.getEmail());
            message.setSubject("【" + mailProperties.getPlatformName() + "】" + title);
            message.setText(content);
            sender.send(message);
            log.info("registration approval mail sent, userId={}", user.getId());
        } catch (Exception e) {
            log.error("mail notify failed, code={}, userId={}", "NOTIFY-MAIL-FAIL", user.getId(), e);
        }
    }

    private String resolveFrom() {
        return StringUtils.hasText(mailProperties.getFrom())
            ? mailProperties.getFrom() : mailProperties.getUsername();
    }
}
