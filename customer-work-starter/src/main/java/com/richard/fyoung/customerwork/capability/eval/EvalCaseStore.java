package com.richard.fyoung.customerwork.capability.eval;

import java.util.List;
import java.util.Optional;

/**
 * 评测用例存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryEvalCaseStore}（空库，此时评测集就是 classpath 里的种子，行为与从前一致）；
 * {@code eval.store-mode=jdbc} 落 {@code cw_eval_case} 表。</p>
 *
 * <p>语义约定：{@link #save} 按 {@code (evalType, caseId)} upsert——同 ID 覆盖，
 * 这既是"修正一条用例"的手段，也是"用库里的版本盖掉种子用例"的手段。</p>
 * @author owlzhangfq@gmail.com
 */
public interface EvalCaseStore {

    /** 保存（新建或覆盖）一条用例。 */
    void save(PersistedEvalCase evalCase);

    /**
     * 批量保存（新建或覆盖）用例。
     *
     * <p>默认实现保持 SPI 向后兼容；JDBC 实现会覆盖为单条批量 SQL，确保导入不会只成功一半。</p>
     */
    default void saveAll(List<PersistedEvalCase> evalCases) {
        for (PersistedEvalCase evalCase : evalCases) {
            save(evalCase);
        }
    }

    /** 按类型取全部用例（含 disabled——合并种子时要靠 disabled 记录去屏蔽同 ID 的种子）。 */
    List<PersistedEvalCase> findByType(EvalType type);

    /** 按类型与用例 ID 查找。 */
    Optional<PersistedEvalCase> find(EvalType type, String caseId);

    /** 删除一条用例（仅能删库里的；种子用例请改用 {@code enabled=false} 覆盖）。 */
    void delete(EvalType type, String caseId);
}
