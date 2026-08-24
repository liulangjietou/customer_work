package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.capability.eval.entity.EvalCaseDO;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalCaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 评测用例存储（生产实现：{@code eval.store-mode=jdbc} 时装配）。
 *
 * <p>让评测集能随 badcase 增长——这是数据飞轮闭合的最后一环：有了 badcase 才有评测用例的来源，
 * 有了评测集才知道改得对不对。</p>
 *
 * <p>{@link #save} 失败抛异常：运营点了"转为评测用例"却静默失败，会让人以为这条 badcase 已经沉淀下来，
 * 实际什么都没留下。读操作降级返回空，评测集退化成纯种子仍可运行。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisEvalCaseStore implements EvalCaseStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisEvalCaseStore.class);

    private final EvalCaseMapper mapper;

    public MybatisEvalCaseStore(EvalCaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(PersistedEvalCase evalCase) {
        if (evalCase == null || evalCase.caseId() == null) {
            return;
        }
        try {
            mapper.upsert(toDO(evalCase));
        } catch (Exception e) {
            log.error("[MybatisEvalCaseStore] save failed, errorCode={}, caseId={}",
                "EVAL-CASE-SAVE-FAIL", evalCase.caseId(), e);
            throw new IllegalStateException("failed to save eval case: " + evalCase.caseId(), e);
        }
    }

    @Override
    public void saveAll(List<PersistedEvalCase> evalCases) {
        if (evalCases == null || evalCases.isEmpty()) {
            return;
        }
        List<EvalCaseDO> rows = new ArrayList<>(evalCases.size());
        for (PersistedEvalCase evalCase : evalCases) {
            if (evalCase == null || evalCase.caseId() == null) {
                throw new IllegalArgumentException("eval case and caseId must not be null");
            }
            rows.add(toDO(evalCase));
        }
        try {
            mapper.upsertBatch(rows);
        } catch (Exception e) {
            log.error("[MybatisEvalCaseStore] batch save failed, errorCode={}, caseCount={}",
                "EVAL-CASE-BATCH-SAVE-FAIL", rows.size(), e);
            throw new IllegalStateException("failed to batch save eval cases", e);
        }
    }

    @Override
    public List<PersistedEvalCase> findByType(EvalType type) {
        try {
            List<EvalCaseDO> rows = mapper.selectByType(type.name());
            List<PersistedEvalCase> result = new ArrayList<>(rows.size());
            for (EvalCaseDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisEvalCaseStore] findByType failed, errorCode={}, evalType={}",
                "EVAL-CASE-FIND-FAIL", type, e);
            return List.of();
        }
    }

    @Override
    public Optional<PersistedEvalCase> find(EvalType type, String caseId) {
        try {
            EvalCaseDO row = mapper.selectByCaseId(type.name(), caseId);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisEvalCaseStore] find failed, errorCode={}, caseId={}",
                "EVAL-CASE-FIND-FAIL", caseId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(EvalType type, String caseId) {
        try {
            mapper.deleteByCaseId(type.name(), caseId);
        } catch (Exception e) {
            log.error("[MybatisEvalCaseStore] delete failed, errorCode={}, caseId={}",
                "EVAL-CASE-DELETE-FAIL", caseId, e);
            throw new IllegalStateException("failed to delete eval case: " + caseId, e);
        }
    }

    private EvalCaseDO toDO(PersistedEvalCase evalCase) {
        EvalCaseDO row = new EvalCaseDO();
        row.setEvalType(evalCase.evalType().name());
        row.setCaseId(evalCase.caseId());
        row.setInput(evalCase.input());
        row.setExpected(evalCase.expected());
        row.setCategory(evalCase.category());
        row.setSource(evalCase.source().name());
        row.setEnabled(evalCase.enabled());
        row.setOriginRef(evalCase.originRef());
        row.setCreatedAtMs(evalCase.createdAtMs());
        return row;
    }

    private PersistedEvalCase toDomain(EvalCaseDO row) {
        return new PersistedEvalCase(
            row.getCaseId(),
            EvalType.valueOf(row.getEvalType()),
            row.getInput(),
            row.getExpected(),
            row.getCategory(),
            EvalCaseSource.valueOf(row.getSource()),
            row.getEnabled() == null || row.getEnabled(),
            row.getOriginRef(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
    }
}
