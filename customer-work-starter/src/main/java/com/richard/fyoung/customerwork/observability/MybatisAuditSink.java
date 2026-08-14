package com.richard.fyoung.customerwork.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.observability.entity.AuditLogDO;
import com.richard.fyoung.customerwork.observability.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 审计落地实现（P3：结构化存储审计轨迹）。
 *
 * <p>把审计事件结构化写入表 {@code cw_audit_log}，支持按会话（{@code agent_name} 后缀 LIKE）回溯审计轨迹，
 * 便于合规审计追溯与故障诊断。默认审计落地仍是 {@link LoggingAuditSink}（写日志），本实现供下游声明
 * 同类型 Bean 覆盖，或直接构造使用。建表由统一 Flyway 迁移负责，本类不建表。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisAuditSink implements AuditSink, AuditQuery {

    private static final Logger log = LoggerFactory.getLogger(MybatisAuditSink.class);

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MybatisAuditSink(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void record(String type, Map<String, Object> fields) {
        if (type == null || fields == null) {
            return;
        }
        try {
            AuditLogDO record = new AuditLogDO();
            record.setEventType(type);
            // agent_name 从 fields 中提取（如果存在）
            record.setAgentName(String.valueOf(fields.getOrDefault("agent", "")));
            record.setEventData(objectMapper.writeValueAsString(fields));
            record.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(record);
        } catch (Exception e) {
            // 写审计失败不影响主链路，只记 error 不抛
            log.error("audit sink record failed, code={}, type={}", "AUDIT-SINK-RECORD-FAIL", type, e);
        }
    }

    @Override
    public List<AuditRecord> queryBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        String pattern = "%" + escapeLike(sessionId);
        try {
            List<AuditLogDO> rows = auditLogMapper.queryBySession(pattern, limit);
            return rows.stream().map(this::toRecord).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("query audit by session failed, code={}, sessionId={}",
                "AUDIT_QUERY_ERROR", sessionId, e);
            return List.of();
        }
    }

    /** 转义 LIKE 通配符，避免 sessionId 中的 % / _ / \ 被当作模式匹配符。 */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private AuditRecord toRecord(AuditLogDO record) {
        long createdAtMs = record.getCreatedAt() == null
            ? 0L
            : record.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new AuditRecord(record.getEventType(), record.getAgentName(), record.getEventData(), createdAtMs);
    }
}
