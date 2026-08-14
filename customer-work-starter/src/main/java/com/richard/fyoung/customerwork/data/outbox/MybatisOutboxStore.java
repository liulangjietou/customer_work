package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.data.outbox.entity.OutboxMessageDO;
import com.richard.fyoung.customerwork.data.outbox.mapper.OutboxMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** MyBatis-Plus Outbox 存储：所有异常 fast-fail，Outbox 是业务一致性的一部分，不能静默降级。 */
public class MybatisOutboxStore implements OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisOutboxStore.class);

    private final OutboxMessageMapper mapper;

    public MybatisOutboxStore(OutboxMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(OutboxMessage message) {
        try {
            mapper.insertMessage(toDO(message));
        } catch (Exception e) {
            log.error("outbox save failed, code={}, id={}", "OUTBOX-STORE-SAVE-FAIL", message.getId(), e);
            throw new IllegalStateException("failed to save outbox message: " + message.getId(), e);
        }
    }

    @Override
    public List<OutboxMessage> claimDue(String owner, long nowMs, long leaseUntilMs, int limit) {
        try {
            List<String> candidates = mapper.selectClaimCandidates(nowMs, limit);
            for (String id : candidates) {
                mapper.claim(id, owner, nowMs, leaseUntilMs);
            }
            return mapper.selectClaimed(owner, leaseUntilMs).stream().map(this::toDomain).toList();
        } catch (Exception e) {
            log.error("outbox claim failed, code={}, owner={}", "OUTBOX-STORE-CLAIM-FAIL", owner, e);
            throw new IllegalStateException("failed to claim outbox messages", e);
        }
    }

    @Override
    public boolean complete(OutboxMessage message, String owner) {
        try {
            return mapper.complete(toDO(message), owner) > 0;
        } catch (Exception e) {
            log.error("outbox complete failed, code={}, id={}",
                "OUTBOX-STORE-COMPLETE-FAIL", message.getId(), e);
            throw new IllegalStateException("failed to complete outbox message: " + message.getId(), e);
        }
    }

    @Override
    public long count(OutboxStatus status) {
        try {
            return mapper.countByStatus(status.name());
        } catch (Exception e) {
            log.error("outbox count failed, code={}, status={}", "OUTBOX-STORE-COUNT-FAIL", status, e);
            throw new IllegalStateException("failed to count outbox messages", e);
        }
    }

    private OutboxMessageDO toDO(OutboxMessage message) {
        OutboxMessageDO row = new OutboxMessageDO();
        row.setId(message.getId());
        row.setTenantId(message.getTenantId());
        row.setType(message.getType());
        row.setAggregateId(message.getAggregateId());
        row.setPayload(message.getPayload());
        row.setStatus(message.getStatus().name());
        row.setAttempts(message.getAttempts());
        row.setNextAttemptAtMs(message.getNextAttemptAtMs());
        row.setLeaseOwner(message.getLeaseOwner());
        row.setLeaseUntilMs(message.getLeaseUntilMs());
        row.setLastError(message.getLastError());
        row.setCreatedAtMs(message.getCreatedAtMs());
        row.setFinishedAtMs(message.getFinishedAtMs());
        return row;
    }

    private OutboxMessage toDomain(OutboxMessageDO row) {
        OutboxMessage message = new OutboxMessage(row.getId(), row.getTenantId(), row.getType(),
            row.getAggregateId(), row.getPayload(), value(row.getCreatedAtMs()));
        message.restore(OutboxStatus.valueOf(row.getStatus()), value(row.getAttempts()),
            value(row.getNextAttemptAtMs()), row.getLeaseOwner(), value(row.getLeaseUntilMs()),
            row.getLastError(), value(row.getFinishedAtMs()));
        return message;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
