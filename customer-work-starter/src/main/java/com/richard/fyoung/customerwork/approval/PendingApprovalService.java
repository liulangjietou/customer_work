package com.richard.fyoung.customerwork.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 待审批单服务（Human-in-the-Loop 闭环的应用层实现）。
 *
 * <p><b>为何在应用层而非绑定框架内部 confirm-sink</b>：AgentScope 2.0-RC4 的运行时确认
 * （{@code RequireUserConfirmEvent} / {@code ReActAgent.CONFIRM_SINK_KEY}）未暴露 Web 友好的
 * 公共回填 API，绑定其内部协议脆弱；而退款等动作的领域模型本就是"先生成待确认工单、人工放行后执行"。
 * 故闭环落在工单审批层，与框架 Permission ASK（工具调用层闸门）<b>互补双层</b>：</p>
 * <ul>
 *   <li>Permission ASK：把关"要不要让 Agent 调用退款工具"；</li>
 *   <li>本服务：把关"工单生成后要不要真打款"，提供 approve/deny 端点闭环。</li>
 * </ul>
 *
 * <p>存储委托给 {@link ApprovalStore} SPI：默认 {@link InMemoryApprovalStore}（进程内，离线可测），
 * 生产可声明自己的 {@link ApprovalStore} Bean（如 JDBC / Redis 实现）覆盖默认，保证审批单重启不丢失。
 * approve 后由下游消费 APPROVED 状态执行实际打款（经 {@link #onApprove} 回调挂接）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class PendingApprovalService {

    private static final Logger log = LoggerFactory.getLogger(PendingApprovalService.class);

    private static final String ID_PREFIX = "AP-";

    private final ApprovalStore store;
    /** 决策回调：approve / deny 后触发，供下游执行实际动作（默认无操作）。 */
    private final AtomicReference<Consumer<ApprovalRequest>> onApprove = new AtomicReference<>(r -> { });
    private final AtomicReference<Consumer<ApprovalRequest>> onDeny = new AtomicReference<>(r -> { });

    /**
     * Spring 注入构造：使用自动装配的 ApprovalStore Bean。
     *
     * <p>必须标 {@code @Autowired}：本类同时存在无参构造，Spring 对「多构造器 + 存在无参 + 无
     * {@code @Autowired}」会回退到无参构造 → 永远用内存实现、审批单重启即丢。</p>
     */
    @Autowired
    public PendingApprovalService(ApprovalStore store) {
        this.store = store;
    }

    /** 无参构造（兼容旧测试与无 Spring 场景）：使用默认内存存储。 */
    public PendingApprovalService() {
        this.store = new InMemoryApprovalStore();
    }

    /** 登记一张待审批单（PENDING）。 */
    public ApprovalRequest submit(ApprovalType type, String sessionId,
                                  String orderId, String amount, String reason) {
        String id = ID_PREFIX + UUID.randomUUID();
        ApprovalRequest req = new ApprovalRequest(id, type, sessionId, orderId, amount, reason,
            System.currentTimeMillis());
        store.save(req);
        log.info("approval submitted: id={}, type={}, order={}, session={}", id, type, orderId, sessionId);
        return req;
    }

    /** 全部审批单（含已决策）。 */
    public List<ApprovalRequest> list() {
        return store.findAll();
    }

    /** 按状态过滤（如只看 PENDING）。 */
    public List<ApprovalRequest> listByStatus(ApprovalStatus status) {
        return store.findByStatus(status);
    }

    public Optional<ApprovalRequest> find(String id) {
        return store.find(id);
    }

    /** 人工放行：推进状态并触发 onApprove 回调（下游执行实际打款）。 */
    public ApprovalRequest approve(String id, String operator) {
        ApprovalRequest req = require(id);
        req.approve(operator, null, System.currentTimeMillis());
        store.update(req);
        log.info("approval approved: id={}, operator={}", id, operator);
        executeApproval(req);
        return req;
    }

    /**
     * 触发下游执行（如实际打款）；失败不回滚已生效的人工决策，只推进 {@link ExecutionStatus}
     * 供 {@link #retryExecutionFailures} 巡检重试与告警——避免"工单显示已放行、钱其实没动"被静默吞掉。
     */
    private void executeApproval(ApprovalRequest req) {
        try {
            onApprove.get().accept(req);
            req.markExecuted();
        } catch (Exception e) {
            req.markExecutionFailed(e.getMessage());
            log.error("approval execution failed, errorCode={}, id={}, attempts={}",
                "APPROVAL-EXEC-FAIL", req.getId(), req.getExecutionAttempts(), e);
        }
        store.update(req);
    }

    /**
     * 巡检重试执行失败的审批单（由 {@code ApprovalTimeoutScheduler} 定期调用）。
     *
     * <p><b>幂等性约定</b>：注册给 {@link #onApprove} 的回调必须对同一
     * {@link ApprovalRequest#getId()} 幂等（如按工单号去重后再打款），否则重试可能导致下游
     * 动作被重复执行——本方法只负责"再触发一次"，不做去重。</p>
     *
     * @param maxAttempts 最大执行尝试次数（含首次执行）；&lt;=1 表示不重试
     * @return 本次实际重试的审批单数
     */
    public int retryExecutionFailures(int maxAttempts) {
        if (maxAttempts <= 1) {
            return 0;
        }
        List<ApprovalRequest> candidates = store.findByStatus(ApprovalStatus.APPROVED).stream()
            .filter(r -> r.getExecutionStatus() == ExecutionStatus.EXECUTE_FAILED)
            .filter(r -> r.getExecutionAttempts() < maxAttempts)
            .collect(Collectors.toList());
        for (ApprovalRequest req : candidates) {
            log.info("approval execution retry: id={}, attempts={}", req.getId(), req.getExecutionAttempts());
            executeApproval(req);
        }
        return candidates.size();
    }

    /** 人工拒绝：推进状态并触发 onDeny 回调；回调失败不影响拒绝决策本身（仅告警）。 */
    public ApprovalRequest deny(String id, String operator, String note) {
        ApprovalRequest req = require(id);
        req.deny(operator, note, System.currentTimeMillis());
        store.update(req);
        log.info("approval denied: id={}, operator={}, note={}", id, operator, note);
        try {
            onDeny.get().accept(req);
        } catch (Exception e) {
            log.error("approval deny callback failed, errorCode={}, id={}", "APPROVAL-DENY-CALLBACK-FAIL", id, e);
        }
        return req;
    }

    /** 挂接 approve 决策回调（下游执行实际动作）。 */
    public void onApprove(Consumer<ApprovalRequest> callback) {
        onApprove.set(callback == null ? r -> { } : callback);
    }

    /** 挂接 deny 决策回调。 */
    public void onDeny(Consumer<ApprovalRequest> callback) {
        onDeny.set(callback == null ? r -> { } : callback);
    }

    /** 单一防御点：审批单必须存在，否则 fast-fail。 */
    private ApprovalRequest require(String id) {
        return store.find(id).orElseThrow(() ->
            new NoSuchElementException("approval not found: " + id));
    }

    /** 暴露存储层（供审批超时巡检等运维任务使用）。 */
    ApprovalStore getStore() {
        return store;
    }
}
