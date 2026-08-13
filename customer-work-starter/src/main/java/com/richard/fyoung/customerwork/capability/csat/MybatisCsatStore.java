package com.richard.fyoung.customerwork.capability.csat;

import com.richard.fyoung.customerwork.capability.csat.entity.CsatSurveyDO;
import com.richard.fyoung.customerwork.capability.csat.mapper.CsatSurveyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus CSAT 存储（{@code csat.store-mode=jdbc} 时装配）。
 *
 * <p>{@link #save} 失败抛异常：用户点了评分却没存下来，这个分就永远丢了——CSAT 是按周按月看的指标，
 * 静默丢分会让趋势失真且无从察觉。读操作降级返回空。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisCsatStore implements CsatStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisCsatStore.class);

    private final CsatSurveyMapper mapper;

    public MybatisCsatStore(CsatSurveyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(CsatSurvey survey) {
        if (survey == null || survey.sessionId() == null) {
            return;
        }
        try {
            mapper.upsert(toDO(survey));
        } catch (Exception e) {
            log.error("[MybatisCsatStore] save failed, errorCode={}, sessionId={}",
                "CSAT-SAVE-FAIL", survey.sessionId(), e);
            throw new IllegalStateException("failed to save csat survey: " + survey.sessionId(), e);
        }
    }

    @Override
    public Optional<CsatSurvey> find(String sessionId) {
        try {
            CsatSurveyDO row = mapper.selectById(sessionId);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisCsatStore] find failed, errorCode={}, sessionId={}",
                "CSAT-FIND-FAIL", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<CsatSurvey> findByWindow(String scopeId, long startMs, long endMs) {
        try {
            List<CsatSurveyDO> rows = mapper.selectByWindow(scopeId, startMs, endMs);
            List<CsatSurvey> result = new ArrayList<>(rows.size());
            for (CsatSurveyDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisCsatStore] findByWindow failed, errorCode={}, scopeId={}",
                "CSAT-WINDOW-FAIL", scopeId, e);
            return List.of();
        }
    }

    private CsatSurveyDO toDO(CsatSurvey survey) {
        CsatSurveyDO row = new CsatSurveyDO();
        row.setSessionId(survey.sessionId());
        row.setScopeId(survey.scopeId());
        row.setScore(survey.score());
        row.setComment(survey.comment());
        row.setInvitedAtMs(survey.invitedAtMs());
        row.setSubmittedAtMs(survey.submittedAtMs());
        return row;
    }

    private CsatSurvey toDomain(CsatSurveyDO row) {
        return new CsatSurvey(
            row.getSessionId(),
            row.getScopeId(),
            row.getScore(),
            row.getComment(),
            row.getInvitedAtMs() == null ? 0L : row.getInvitedAtMs(),
            row.getSubmittedAtMs() == null ? 0L : row.getSubmittedAtMs());
    }
}
