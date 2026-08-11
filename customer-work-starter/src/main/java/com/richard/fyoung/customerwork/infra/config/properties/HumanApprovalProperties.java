package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Human-in-the-Loop 人工确认配置。 */
@Data
public class HumanApprovalProperties {
    /** 是否启用工具级人工确认（高风险工具执行后暂停 Agent 待人工复核）。 */
    private boolean enabled = true;
    /** 受控（需人工确认）的工具名集合。 */
    private List<String> guardedTools = new ArrayList<>(List.of("submitRefund"));
    /** 审批超时（秒）：PENDING 超过该时间未决策则自动超时处理；<=0 禁用。 */
    private long timeoutSeconds = 0;
    /** 超时动作：escalate（升级转人工）| deny（自动拒绝）。 */
    private String timeoutAction = "escalate";
    /** 审批存储模式：memory（进程内，默认）| jdbc（数据库持久化）。 */
    private String storeMode = "memory";
    /** 审批放行后下游执行（如实际打款）失败的最大重试次数（含首次执行）；&lt;=1 表示不重试。 */
    private int maxExecutionRetryAttempts = 3;
}
