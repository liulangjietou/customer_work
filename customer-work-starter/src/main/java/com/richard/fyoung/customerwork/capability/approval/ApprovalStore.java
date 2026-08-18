package com.richard.fyoung.customerwork.capability.approval;

import java.util.List;
import java.util.Optional;

/**
 * 审批工单存储 SPI（持久化扩展点）。
 *
 * <p>默认由 {@link InMemoryApprovalStore} 提供进程内实现（离线可测、确定性）；
 * 生产可替换为 JDBC / Redis 等持久化实现（声明同类型 Bean 覆盖默认），保证审批单重启不丢失——
 * 这对涉及资金的退款审批至关重要。</p>
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>{@link #save} 是 upsert（新建或更新已有审批单）；</li>
 *   <li>{@link #update} 在审批单状态变更后持久化最新状态（实现须保证线程安全）；</li>
 *   <li>返回的 {@link ApprovalRequest} 须为同一引用或等值副本，调用方可安全操作其状态字段。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public interface ApprovalStore {

    /** 保存（新建或覆盖）一张审批单。 */
    void save(ApprovalRequest request);

    /** 按 ID 查找审批单。 */
    Optional<ApprovalRequest> find(String id);

    /** 全部审批单（含已决策）。 */
    List<ApprovalRequest> findAll();

    /** 按状态过滤（如只看 PENDING）。 */
    List<ApprovalRequest> findByStatus(ApprovalStatus status);

    /** 更新审批单（状态变更后调用）。 */
    void update(ApprovalRequest request);

    /** PENDING 到最终人工决策的原子条件更新；只有一个并发决策者能成功。 */
    boolean decide(String id, ApprovalStatus target, String operator, String note, long decidedAtMs);

    /** 原子取得带 fencing token 的执行租约并累计尝试次数。 */
    boolean claimExecution(String id, int maxAttempts, long startedAtMs, String fencingToken);

    /** 仅当前 fencing token 持有者可写入执行结果。 */
    boolean completeExecution(String id, String fencingToken, boolean success, String failureReason);

    /** 把崩溃遗留且已超时的执行租约转为可重试失败态。 */
    int recoverStuckExecutions(long startedBeforeMs);

    /** 删除审批单（仅用于管理/清理，不改变业务状态机）。 */
    void delete(String id);
}
