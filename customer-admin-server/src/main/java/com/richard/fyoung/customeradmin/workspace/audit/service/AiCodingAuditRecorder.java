package com.richard.fyoung.customeradmin.workspace.audit.service;

import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.mapper.AiCodingAuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计日志异步落库执行器。与 {@link AiCodingAuditService} 拆成两个类的原因：{@code @Async}
 * 依赖 Spring 代理，同类内部 this 调用不会走代理导致注解静默失效，跨类调用才真正异步。
 *
 * <p>审计是旁路能力：落库失败只记 error 日志，绝不向业务主链路抛异常。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AiCodingAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiCodingAuditRecorder.class);

    private final AiCodingAuditLogMapper auditLogMapper;

    public AiCodingAuditRecorder(AiCodingAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Async
    public void persist(AiCodingAuditLog entry) {
        try {
            if (entry.getCreateTime() == null) {
                entry.setCreateTime(LocalDateTime.now());
            }
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.error("persist ai coding audit log failed, code={}, operation={}, agentCode={}, sessionId={}",
                "AI-CODING-AUDIT-PERSIST-FAIL", entry.getOperation(), entry.getAgentCode(), entry.getSessionId(), e);
        }
    }
}
