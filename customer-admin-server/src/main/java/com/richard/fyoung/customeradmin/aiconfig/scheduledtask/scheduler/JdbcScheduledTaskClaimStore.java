package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 基于数据库唯一键的 cron 触发认领，数据库是多 Pod 之间唯一共享的裁决点。 */
@Component
public class JdbcScheduledTaskClaimStore implements ScheduledTaskClaimStore {

    private static final String CLAIM_SQL = """
        INSERT IGNORE INTO ai_scheduled_task_claim
            (tenant_id, task_id, task_code, fire_time, owner_id, claim_time)
        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
        """;

    private final JdbcTemplate jdbcTemplate;
    private final String ownerId = UUID.randomUUID().toString();

    public JdbcScheduledTaskClaimStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean claim(String tenantId, Long taskId, String taskCode, Instant scheduledFireTime) {
        int inserted = jdbcTemplate.update(CLAIM_SQL, tenantId, taskId, taskCode,
            Timestamp.from(scheduledFireTime), ownerId);
        return inserted == 1;
    }
}
