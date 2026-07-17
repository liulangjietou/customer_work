package com.richard.fyoung.customerwork.diagnostics;

import com.richard.fyoung.customerwork.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.observability.AuditRecord;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话故障诊断全景（一次拉齐分散在多个存储里的会话状态，供线上定位）。
 *
 * <p>把此前需要人肉查 4 张表 + grep 日志才能拼出的一次会话现场，聚合为单个只读视图：
 * 会话状态是否存在、对话阶段、槽位收集进度、关联审批单（含执行状态）、最近审计事件、质检失败事实。</p>
 *
 * <p>{@link #degradedSources} 诚实标注哪些数据源采集失败或不可用——让"某段为空"能区分
 * "确实没有该类事件" 与 "该数据源没接入/查询出错"，避免误判。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class SessionDiagnostic {

    /** 被诊断的会话 ID。 */
    private String sessionId;
    /** 解析出的租户 ID（长期记忆 / 事实流水的分区维度）。 */
    private String tenantId;
    /** 该会话是否已有持久化短期状态（StateStore）。 */
    private boolean stateExists;
    /** 当前对话阶段（默认 GREETING）。 */
    private String dialogStage;
    /** 进行中的槽位收集：已收集字段（无进行中表单则为 null）。 */
    private Map<String, String> slotFilling;
    /** 槽位收集当前正在追问的字段名（无则为 null）。 */
    private String slotFillingAsking;
    /** 关联的审批单（按 sessionId 过滤，含状态与执行状态）。 */
    private List<ApprovalRequest> approvals = new ArrayList<>();
    /** 审计查询后端是否可用（false 表示未接入可查询的审计实现，如仅 LoggingAuditSink）。 */
    private boolean auditAvailable;
    /** 最近的审计事件（时间倒序，需接入 MybatisAuditSink 等可查询实现）。 */
    private List<AuditRecord> recentAudit = new ArrayList<>();
    /** 该会话相关的质检失败事实（数据飞轮 L3，JSONL 原文）。 */
    private List<String> qualityFacts = new ArrayList<>();
    /** 采集失败/不可用的数据源标注（形如 {@code "audit: connection refused"}）。 */
    private List<String> degradedSources = new ArrayList<>();
}
