package com.richard.fyoung.customeradmin.governance.change.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.governance.change.GovernanceProperties;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernanceAuditEvent;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernanceAuditEventMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** 在变更状态事务内同步追加哈希链审计；写失败即阻止状态推进。 */
@Component
public class GovernanceAuditWriter {

    private static final String GENESIS_HASH = "0".repeat(64);

    private final GovernanceAuditEventMapper mapper;
    private final GovernanceProperties properties;

    public GovernanceAuditWriter(GovernanceAuditEventMapper mapper, GovernanceProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public void append(AiGovernedChangeRequest request, String eventType,
                       Long actorId, String actorName, String detail, LocalDateTime now) {
        AiGovernanceAuditEvent previous = mapper.selectOne(
            new LambdaQueryWrapper<AiGovernanceAuditEvent>()
                .eq(AiGovernanceAuditEvent::getRequestId, request.getId())
                .orderByDesc(AiGovernanceAuditEvent::getSequenceNo)
                .last("LIMIT 1"));
        int sequence = previous == null ? 1 : previous.getSequenceNo() + 1;
        String previousHash = previous == null ? GENESIS_HASH : previous.getEventHash();
        String canonical = String.join("\u001f", request.getTenantId(), request.getId(),
            Integer.toString(sequence), eventType, actorId == null ? "" : actorId.toString(),
            request.getPayloadHash(), detail == null ? "" : detail, previousHash, now.toString());

        AiGovernanceAuditEvent event = new AiGovernanceAuditEvent();
        event.setTenantId(request.getTenantId());
        event.setRequestId(request.getId());
        event.setSequenceNo(sequence);
        event.setEventType(eventType);
        event.setActorId(actorId);
        event.setActorName(actorName);
        event.setPayloadHash(request.getPayloadHash());
        event.setDetail(detail);
        event.setPreviousHash(previousHash);
        event.setEventHash(sha256(canonical));
        event.setRetentionUntil(now.plusDays(properties.effectiveAuditRetentionDays()));
        event.setCreateTime(now);
        if (mapper.insert(event) != 1) {
            throw new IllegalStateException("governance audit event was not persisted");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
