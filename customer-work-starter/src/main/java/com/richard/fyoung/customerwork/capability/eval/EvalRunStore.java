package com.richard.fyoung.customerwork.capability.eval;

import java.util.List;
import java.util.Optional;

/**
 * 评测运行记录存储 SPI（持久化扩展点）。
 *
 * <p>默认由 {@link InMemoryEvalRunStore} 提供进程内实现（离线可测、确定性）；
 * 生产切 {@code eval.store-mode=jdbc} 落 {@code cw_eval_run} 表——评测的价值全在纵向对比上，
 * 进程内存储一重启就没了基线，"这版比上版好还是坏"当场失去答案。</p>
 *
 * <p>语义约定：{@link #save} 按 {@code runId} 插入，运行记录是只追加的事实，不做更新。</p>
 * @author owlzhangfq@gmail.com
 */
public interface EvalRunStore {

    /** 保存一次运行记录（只追加）。 */
    void save(EvalRun run);

    /** 按运行 ID 查找。 */
    Optional<EvalRun> find(String runId);

    /** 按类型取最近若干次运行，按写入顺序倒序（最新在前）。 */
    List<EvalRun> findRecent(EvalType type, int limit);

    /**
     * 取某次运行<b>之前</b>最近的一次同类型运行，即对比用的基线。
     *
     * <p><b>按写入顺序而非时间戳定位</b>：意图评测是纯内存计算，连续两次能落在同一毫秒里，
     * 用 {@code createdAtMs <} 比较会让第二次运行找不到基线（同毫秒下该条件不成立），
     * CI 里连跑两次必然踩到。故以入库序为准——{@code cw_fact_log} 早就是这么做的。</p>
     *
     * @param type  评测类型
     * @param runId 本次运行 ID（须已入库）
     */
    Optional<EvalRun> findBaseline(EvalType type, String runId);
}
