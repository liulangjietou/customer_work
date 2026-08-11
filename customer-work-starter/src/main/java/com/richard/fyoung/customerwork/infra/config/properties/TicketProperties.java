package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能客服工单配置。
 *
 * <p>{@code store-mode} 决定 {@code TicketService} 的持久化方式（memory 单实例 / jdbc 跨实例共享）；
 * {@code handoff-keywords} 供上层意图识别命中即建单转人工；SLA 阈值中 waiting/processing 仅告警，
 * auto-confirm/auto-close 由 {@code TicketSlaScheduler} 做有意的自动流转兜底。</p>
 */
@Data
public class TicketProperties {
    /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
    /** 转人工触发关键词（命中即请求转人工）。 */
    private List<String> handoffKeywords = new ArrayList<>(List.of(
        "转人工", "人工客服", "人工服务", "真人客服", "找人工"));
    /** SLA 告警：WAITING_AGENT（无人接单）超过该秒数即 error 告警。 */
    private long slaWaitingSeconds = 300;
    /** SLA 告警：PROCESSING（接单未处理完）超过该秒数即 error 告警。 */
    private long slaProcessingSeconds = 1800;
    /** WAITING_CONFIRM 超过该秒数用户未确认即自动确认（默认 1 天）。 */
    private long autoConfirmSeconds = 86400;
    /** RESOLVED 超过该秒数即自动关闭归档（默认 3 天）。 */
    private long autoCloseSeconds = 259200;
    /** 进行中工单用户空闲超过该秒数即强制关闭（默认 5 分钟，0=禁用）。 */
    private long idleCloseSeconds = 300;
    /** SLA 巡检总开关（关闭则告警与自动流转全部停用）。 */
    private boolean slaEnabled = true;
}
