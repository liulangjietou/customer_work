package com.richard.fyoung.customerwork.capability.approval;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审批超时巡检器（P1：审批超时自动处理）。
 *
 * <p>周期性扫描 PENDING 审批单，超过 {@code human-approval.timeout-seconds} 未决策的按配置策略处理：</p>
 * <ul>
 *   <li><b>escalate</b>（默认）：保持 PENDING 但记录告警日志，等待人工升级处理；</li>
 *   <li><b>deny</b>：自动拒绝，释放占用的资源（适用于"超时视为放弃"场景）。</li>
 * </ul>
 *
 * <p>仅在 {@code human-approval.timeout-seconds > 0} 时生效；默认 0 = 禁用。</p>
 *
 * <p>与 {@link PendingApprovalService} 的关系：巡检器只读扫描 + 委托 deny，
 * 不引入新状态（避免状态机膨胀）；approve 仍需人工。</p>
 *
 * <p>同时承担审批<b>执行失败重试</b>：对 {@code APPROVED} 但下游回调（如打款）失败的工单，按
 * {@code human-approval.max-execution-retry-attempts} 周期性重试，见 {@link #retryExecutionFailures()}。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ApprovalTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutScheduler.class);

    private final CustomerWorkProperties properties;
    private final PendingApprovalService approvalService;

    public ApprovalTimeoutScheduler(CustomerWorkProperties properties,
                                     PendingApprovalService approvalService) {
        this.properties = properties;
        this.approvalService = approvalService;
    }

    @Scheduled(fixedDelayString = "${customer-work.runtime.scheduler-fixed-delay-ms:60000}")
    public void checkTimeouts() {
        long timeoutSeconds = properties.getHumanApproval().getTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return;
        }
        String action = properties.getHumanApproval().getTimeoutAction();
        long now = System.currentTimeMillis();
        long threshold = now - timeoutSeconds * 1000;

        List<ApprovalRequest> pending = approvalService.listByStatus(ApprovalStatus.PENDING);
        for (ApprovalRequest req : pending) {
            if (req.getCreatedAtMs() < threshold) {
                handleTimeout(req, action);
            }
        }
    }

    /** 处理单条超时审批（抽出以便单测）。 */
    void handleTimeout(ApprovalRequest req, String action) {
        if ("deny".equalsIgnoreCase(action)) {
            try {
                approvalService.deny(req.getId(), "system-timeout",
                    "审批超时自动拒绝（timeout=" + properties.getHumanApproval().getTimeoutSeconds() + "s）");
                log.info("approval auto-denied due to timeout: id={}, type={}, createdAt={}",
                    req.getId(), req.getType(), req.getCreatedAtMs());
            } catch (Exception e) {
                log.error("approval auto-deny failed, code={}, id={}",
                    "APPROVAL-TIMEOUT-DENY-FAIL", req.getId(), e);
            }
        } else {
            // escalate：记录告警，保持 PENDING 等待人工升级
            log.info("approval timeout escalation: id={}, type={}, order={}, session={}, createdAt={}, age={}s",
                req.getId(), req.getType(), req.getOrderId(), req.getSessionId(),
                req.getCreatedAtMs(), (System.currentTimeMillis() - req.getCreatedAtMs()) / 1000);
        }
    }

    /** 可被单测调用的同步入口（跳过 @Scheduled 注解）。 */
    public void runTimeoutCheck() {
        checkTimeouts();
    }

    /** 巡检重试执行失败的审批单（如打款回调异常）；{@code maxExecutionRetryAttempts<=1} 时禁用。 */
    @Scheduled(fixedDelayString = "${customer-work.runtime.scheduler-fixed-delay-ms:60000}")
    public void retryExecutionFailures() {
        int maxAttempts = properties.getHumanApproval().getMaxExecutionRetryAttempts();
        int retried = approvalService.retryExecutionFailures(maxAttempts);
        if (retried > 0) {
            log.info("approval execution retry batch: retried={}", retried);
        }
    }
}
