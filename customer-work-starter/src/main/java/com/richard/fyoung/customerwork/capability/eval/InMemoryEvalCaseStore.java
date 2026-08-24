package com.richard.fyoung.customerwork.capability.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内评测用例存储（默认实现）。
 *
 * <p>默认为空：此时评测集完全等于 classpath 里的种子，与引入本 SPI 之前的行为一致——
 * 不开 jdbc 的部署不会因为多了这层而改变评测结果。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryEvalCaseStore implements EvalCaseStore {

    private final Map<String, PersistedEvalCase> cases = new ConcurrentHashMap<>();

    @Override
    public void save(PersistedEvalCase evalCase) {
        if (evalCase == null || evalCase.caseId() == null) {
            return;
        }
        cases.put(key(evalCase.evalType(), evalCase.caseId()), evalCase);
    }

    @Override
    public synchronized void saveAll(List<PersistedEvalCase> evalCases) {
        if (evalCases == null || evalCases.isEmpty()) {
            return;
        }
        for (PersistedEvalCase evalCase : evalCases) {
            if (evalCase == null || evalCase.caseId() == null) {
                throw new IllegalArgumentException("eval case and caseId must not be null");
            }
        }
        for (PersistedEvalCase evalCase : evalCases) {
            cases.put(key(evalCase.evalType(), evalCase.caseId()), evalCase);
        }
    }

    @Override
    public List<PersistedEvalCase> findByType(EvalType type) {
        List<PersistedEvalCase> matched = new ArrayList<>();
        for (PersistedEvalCase evalCase : cases.values()) {
            if (evalCase.evalType() == type) {
                matched.add(evalCase);
            }
        }
        matched.sort(Comparator.comparing(PersistedEvalCase::caseId));
        return List.copyOf(matched);
    }

    @Override
    public Optional<PersistedEvalCase> find(EvalType type, String caseId) {
        return Optional.ofNullable(cases.get(key(type, caseId)));
    }

    @Override
    public void delete(EvalType type, String caseId) {
        cases.remove(key(type, caseId));
    }

    /** 类型 + 用例 ID 才是唯一键：两类评测各有各的编号空间，允许重名。 */
    private String key(EvalType type, String caseId) {
        return type.name() + '#' + caseId;
    }
}
