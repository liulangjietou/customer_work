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
 * 读操作降级返回空，不因查询故障连累主链路。</p>
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
            return Optional.empty();
        }
    }

    @Override
    public List<DeadLetter> findDue(long nowMs, int limit) {
        try {
            return toDomain(mapper.selectDue(nowMs, limit));
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] findDue failed, errorCode={}", "DEADLETTER-STORE-DUE-FAIL", e);
            return List.of();
        }
    }

    @Override
    public List<DeadLetter> findByStatus(DeadLetterStatus status, int limit) {
        try {
            return toDomain(mapper.selectByStatus(status.name(), limit));
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] findByStatus failed, errorCode={}, status={}",
                "DEADLETTER-STORE-STATUS-FAIL", status, e);
            return List.of();
        }
    }

    @Override
    public long count(DeadLetterStatus status) {
        try {
            return mapper.countByStatus(status.name());
        } catch (Exception e) {
            log.error("[MybatisDeadLetterStore] count failed, errorCode={}, status={}",
                "DEADLETTER-STORE-COUNT-FAIL", status, e);
            return 0L;
        }
    }

    private DeadLetterDO toDO(DeadLetter letter) {
        DeadLetterDO row = new DeadLetterDO();
        row.setId(letter.getId());
        row.setType(letter.getType());
        row.setPayload(letter.getPayload());
        row.setBizKey(letter.getBizKey());
        row.setStatus(letter.getStatus().name());
        row.setAttempts(letter.getAttempts());
        row.setLastError(letter.getLastError());
        row.setNextRetryAtMs(letter.getNextRetryAtMs());
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
        DeadLetter letter = new DeadLetter(row.getId(), row.getType(), row.getPayload(),
            row.getBizKey(), row.getLastError(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
        letter.restoreState(
            DeadLetterStatus.valueOf(row.getStatus()),
            row.getAttempts() == null ? 0 : row.getAttempts(),
            row.getLastError(),
            row.getNextRetryAtMs() == null ? 0L : row.getNextRetryAtMs(),
            row.getFinishedAtMs() == null ? 0L : row.getFinishedAtMs());
        return letter;
    }
}
