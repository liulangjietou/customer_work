package com.richard.fyoung.customerwork.capability.badcase;

import com.richard.fyoung.customerwork.capability.badcase.entity.BadcaseDO;
import com.richard.fyoung.customerwork.capability.badcase.mapper.BadcaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus badcase 存储（生产实现：{@code badcase.store-mode=jdbc} 时装配）。
 *
 * <p>{@link #save} 失败抛异常：badcase 的整个价值就是"攒下来集中筛"，静默丢失等于飞轮空转；
 * 而且状态流转（采纳/忽略）若写失败却不报错，运营会以为处理过了，同一条会被反复翻出来。
 * 读操作降级返回空，不因查询故障阻断其他功能。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisBadcaseStore implements BadcaseStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisBadcaseStore.class);

    private final BadcaseMapper mapper;

    public MybatisBadcaseStore(BadcaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(Badcase badcase) {
        if (badcase == null || badcase.getId() == null) {
            return;
        }
        try {
            mapper.upsert(toDO(badcase));
        } catch (Exception e) {
            log.error("[MybatisBadcaseStore] save failed, errorCode={}, id={}",
                "BADCASE-SAVE-FAIL", badcase.getId(), e);
            throw new IllegalStateException("failed to save badcase: " + badcase.getId(), e);
        }
    }

    @Override
    public Optional<Badcase> find(String id) {
        try {
            BadcaseDO row = mapper.selectById(id);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisBadcaseStore] find failed, errorCode={}, id={}",
                "BADCASE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public List<Badcase> query(BadcaseQuery query) {
        try {
            List<BadcaseDO> rows = mapper.selectByCondition(
                query.status() == null ? null : query.status().name(),
                query.source() == null ? null : query.source().name(),
                Math.max(query.offset(), 0),
                Math.max(query.limit(), 0));
            List<Badcase> result = new ArrayList<>(rows.size());
            for (BadcaseDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisBadcaseStore] query failed, errorCode={}, status={}",
                "BADCASE-QUERY-FAIL", query.status(), e);
            return List.of();
        }
    }

    @Override
    public long count(BadcaseStatus status, BadcaseSource source) {
        try {
            return mapper.countByCondition(status == null ? null : status.name(),
                source == null ? null : source.name());
        } catch (Exception e) {
            log.error("[MybatisBadcaseStore] count failed, errorCode={}, status={}",
                "BADCASE-COUNT-FAIL", status, e);
            return 0L;
        }
    }

    private BadcaseDO toDO(Badcase badcase) {
        BadcaseDO row = new BadcaseDO();
        row.setId(badcase.getId());
        row.setSource(badcase.getSource().name());
        row.setSessionId(badcase.getSessionId());
        row.setMessageId(badcase.getMessageId());
        row.setUserInput(badcase.getUserInput());
        row.setAgentReply(badcase.getAgentReply());
        row.setSignalHash(badcase.getSignalHash());
        row.setDetail(badcase.getDetail());
        row.setStatus(badcase.getStatus().name());
        row.setAdoptedKnowledgeId(badcase.getAdoptedKnowledgeId());
        row.setAdoptedEvalCaseId(badcase.getAdoptedEvalCaseId());
        row.setHandledBy(badcase.getHandledBy());
        row.setHandledAtMs(badcase.getHandledAtMs());
        row.setIgnoreReason(badcase.getIgnoreReason());
        row.setCreatedAtMs(badcase.getCreatedAtMs());
        return row;
    }

    private Badcase toDomain(BadcaseDO row) {
        Badcase badcase = new Badcase(
            row.getId(),
            BadcaseSource.valueOf(row.getSource()),
            row.getSessionId(),
            row.getMessageId(),
            row.getUserInput(),
            row.getAgentReply(),
            row.getSignalHash(),
            row.getDetail(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
        badcase.restoreState(
            BadcaseStatus.valueOf(row.getStatus()),
            row.getAdoptedKnowledgeId(),
            row.getAdoptedEvalCaseId(),
            row.getHandledBy(),
            row.getHandledAtMs() == null ? 0L : row.getHandledAtMs(),
            row.getIgnoreReason());
        return badcase;
    }
}
