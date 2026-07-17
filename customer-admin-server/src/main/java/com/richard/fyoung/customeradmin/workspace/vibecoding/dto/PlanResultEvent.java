package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/**
 * {@code plan_result} SSE 事件载荷：某个 {@code planId} 被解决后下发，供前端把确认卡片从"等待"翻成终态。
 *
 * <p>为什么需要它：用户主动点批准/拒绝时前端能从 {@code POST /plan/confirm} 的响应拿到结果，但
 * <b>超时（服务端自动拒绝）</b>没有对应的请求响应，只能靠这条 SSE 事件通知前端停掉倒计时。</p>
 *
 * @param planId 被解决的计划 ID
 * @param status 终态：{@code APPROVED}｜{@code REJECTED}｜{@code TIMEOUT}
 * @author owlzhangfq@gmail.com
 */
public record PlanResultEvent(String planId, String status) {

    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
}
