package com.richard.fyoung.customerwork.capability.deadletter;

import com.richard.fyoung.customerwork.capability.deadletter.entity.DeadLetterDO;
import com.richard.fyoung.customerwork.capability.deadletter.mapper.DeadLetterMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 死信存储（{@code dead-letter.store-mode=jdbc} 时装配）。
 *
 * <p>{@link #save} 失败抛异常：死信本身就是"这笔别丢了"的凭据，它自己丢了就真的没人知道了。
 * 读写异常均 fast-fail：运营重投与状态查询必须反映权威数据库事实。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisDeadLetterStore implements DeadLetterStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisDeadLetterStore.class);

    private final DeadLetterMapper mapper;

    public MybatisDeadLetterStore(DeadLetterMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(DeadLetter letter) {
        if (letter == null || letter.getId() == null) {
            return;
        }
        try {
            mapper.upsert(toDO(letter));
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] save failed, errorCode={}, id={}",
                "DEADLETTER-STORE-SAVE-FAIL", letter.getId(), e);
            throw new IllegalStateException("failed to save dead letter: " + letter.getId(), e);
        }
    }

    @Override
    public Optional<DeadLetter> find(String id) {
        try {
            DeadLetterDO row = mapper.selectById(id);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] find failed, errorCode={}, id={}",
                "DEADLETTER-STORE-FIND-FAIL", id, e);
            throw new IllegalStateException("failed to find dead letter: " + id, e);
        }
    }

    @Override
    public List<DeadLetter> claimDue(String owner, long nowMs, long leaseUntilMs, int limit) {
        try {
            List<String> candidates = mapper.selectClaimCandidates(nowMs, limit);
            for (String id : candidates) {
                mapper.claim(id, owner, nowMs, leaseUntilMs);
            }
            return toDomain(mapper.selectClaimed(owner, leaseUntilMs));
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] claimDue failed, errorCode={}, owner={}",
                "DEADLETTER-STORE-CLAIM-FAIL", owner, e);
            throw new IllegalStateException("failed to claim dead letters", e);
        }
    }

    @Override
    public boolean complete(DeadLetter letter, String owner) {
        try {
            return mapper.complete(toDO(letter), owner) > 0;
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] complete failed, errorCode={}, id={}",
                "DEADLETTER-STORE-COMPLETE-FAIL", letter.getId(), e);
            throw new IllegalStateException("failed to complete dead letter: " + letter.getId(), e);
        }
    }

    @Override
    public List<DeadLetter> findByStatus(DeadLetterStatus status, int limit) {
        try {
            return toDomain(mapper.selectByStatus(status.name(), limit));
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] findByStatus failed, errorCode={}, status={}",
                "DEADLETTER-STORE-STATUS-FAIL", status, e);
            throw new IllegalStateException("failed to list dead letters", e);
        }
    }

    @Override
    public long count(DeadLetterStatus status) {
        try {
            return mapper.countByStatus(status.name());
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] count failed, errorCode={}, status={}",
                "DEADLETTER-STORE-COUNT-FAIL", status, e);
            throw new IllegalStateException("failed to count dead letters", e);
        }
    }

    private DeadLetterDO toDO(DeadLetter letter) {
        DeadLetterDO row = new DeadLetterDO();
        row.setId(letter.getId());
        row.setTenantId(letter.getTenantId());
        row.setType(letter.getType());
        row.setPayload(letter.getPayload());
        row.setBizKey(letter.getBizKey());
        row.setStatus(letter.getStatus().name());
        row.setAttempts(letter.getAttempts());
        row.setLastError(letter.getLastError());
        row.setNextRetryAtMs(letter.getNextRetryAtMs());
        row.setLeaseOwner(letter.getLeaseOwner());
        row.setLeaseUntilMs(letter.getLeaseUntilMs());
        row.setCreatedAtMs(letter.getCreatedAtMs());
        row.setFinishedAtMs(letter.getFinishedAtMs());
        return row;
    }

    private List<DeadLetter> toDomain(List<DeadLetterDO> rows) {
        List<DeadLetter> result = new ArrayList<>(rows.size());
        for (DeadLetterDO row : rows) {
            result.add(toDomain(row));
        }
        return result;
    }

    private DeadLetter toDomain(DeadLetterDO row) {
        DeadLetter letter = new DeadLetter(row.getId(), row.getTenantId(), row.getType(), row.getPayload(),
            row.getBizKey(), row.getLastError(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
        letter.restoreState(
            DeadLetterStatus.valueOf(row.getStatus()),
            row.getAttempts() == null ? 0 : row.getAttempts(),
            row.getLastError(),
            row.getNextRetryAtMs() == null ? 0L : row.getNextRetryAtMs(),
            row.getFinishedAtMs() == null ? 0L : row.getFinishedAtMs(),
            row.getLeaseOwner(), row.getLeaseUntilMs() == null ? 0L : row.getLeaseUntilMs());
        return letter;
    }
}
