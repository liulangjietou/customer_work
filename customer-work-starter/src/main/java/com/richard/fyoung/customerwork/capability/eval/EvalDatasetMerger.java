package com.richard.fyoung.customerwork.capability.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测集合并：classpath 种子 + 库中用例。
 *
 * <p>两类评测的 Runner 都要做同一件事，合并规则只该有一份——分成两份迟早漂移，
 * 而漂移的表现是"同样加一条用例，意图评测生效了、质量评测没有"。</p>
 *
 * <p><b>为什么种子仍留在 classpath</b>：种子随代码走、改动经过 code review，是评测集的基准线；
 * 全量搬进库会让"这套系统的基准评测集长什么样"变成一个要连数据库才能回答的问题。
 * 库只承载增量与修正。</p>
 * @author owlzhangfq@gmail.com
 */
final class EvalDatasetMerger {

    private EvalDatasetMerger() {
    }

    /**
     * 合并：同 {@code caseId} 以库中版本为准，库中新增的追加在种子之后，最后滤掉停用的。
     *
     * <p>"以库为准"同时提供了两种能力：修正一条种子用例的期望值，以及用一条 {@code enabled=false}
     * 的记录屏蔽掉某条种子用例——都不需要改代码发版。</p>
     *
     * <p>用 {@link LinkedHashMap} 保序：种子在前、增量在后，且覆盖种子时保持其原位置。
     * 评测集顺序稳定，报告里的失败列表才能跨版本对照着看。</p>
     */
    static List<PersistedEvalCase> merge(List<PersistedEvalCase> seeds, List<PersistedEvalCase> stored) {
        Map<String, PersistedEvalCase> merged = new LinkedHashMap<>();
        for (PersistedEvalCase seed : seeds) {
            merged.put(seed.caseId(), seed);
        }
        for (PersistedEvalCase persisted : stored) {
            merged.put(persisted.caseId(), persisted);
        }
        List<PersistedEvalCase> result = new ArrayList<>(merged.size());
        for (PersistedEvalCase evalCase : merged.values()) {
            if (evalCase.enabled()) {
                result.add(evalCase);
            }
        }
        return List.copyOf(result);
    }
}
