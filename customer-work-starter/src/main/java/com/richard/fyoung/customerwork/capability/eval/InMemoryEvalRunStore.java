package com.richard.fyoung.customerwork.capability.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内评测运行记录存储（默认实现，离线可测）。
 *
 * <p>重启即清空，因而<b>会丢掉对比基线</b>；仅适合单测与本地试跑，生产请切
 * {@code eval.store-mode=jdbc}。</p>
 *
 * <p>用 {@link LinkedHashMap} 而非普通哈希表：顺序即写入序，与 jdbc 实现的自增 {@code seq}
 * 语义一致。评测跑得很快，连续两次能落在同一毫秒里，按时间戳定序会让第二次找不到基线。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryEvalRunStore implements EvalRunStore {

    /** 保插入顺序；并发写由外层同步包装。 */
    private final Map<String, EvalRun> runs = Collections.synchronizedMap(new LinkedHashMap<>());

    @Override
    public void save(EvalRun run) {
        if (run == null || run.runId() == null) {
            return;
        }
        runs.put(run.runId(), run);
    }

    @Override
    public Optional<EvalRun> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<EvalRun> findRecent(EvalType type, int limit) {
        List<EvalRun> matched = ofType(type);
        Collections.reverse(matched);
        return List.copyOf(matched.subList(0, Math.min(Math.max(limit, 0), matched.size())));
    }

    @Override
    public Optional<EvalRun> findBaseline(EvalType type, String runId) {
        List<EvalRun> matched = ofType(type);
        for (int i = matched.size() - 1; i >= 0; i--) {
            if (matched.get(i).runId().equals(runId)) {
                return i > 0 ? Optional.of(matched.get(i - 1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 同类型运行，按写入顺序正序。 */
    private List<EvalRun> ofType(EvalType type) {
        List<EvalRun> matched = new ArrayList<>();
        synchronized (runs) {
            for (EvalRun run : runs.values()) {
                if (run.evalType() == type) {
                    matched.add(run);
                }
            }
        }
        return matched;
    }
}
