package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/**
 * 协作模式（P3-1）角色阶段事件：多角色顺序流水中，某个角色的开始/完成/失败通知，走 SSE
 * {@code role_stage} 事件推送。前端据此把一次协作编程渲染成"需求分析 → 方案设计 → 编码实现 →
 * 自测审查"的阶段卡片时间线。
 *
 * <p>编码角色（{@link #TYPE_CODING}）的实际产物（file_change/test_report/正文增量）仍走既有
 * VibeCoding SSE 事件体系推送，本事件只标记该角色阶段的边界；非编码角色（分析/设计/审查）的文本
 * 结论直接放在 {@link #output} 里。</p>
 *
 * @param role       角色展示名（如"需求分析师"）
 * @param type       角色类型：{@link #TYPE_PLAN}｜{@link #TYPE_CODING}｜{@link #TYPE_REVIEW}
 * @param index      角色在流水中的序号（1 起）
 * @param total      流水角色总数
 * @param status     阶段状态：{@link #STATUS_START}｜{@link #STATUS_DONE}｜{@link #STATUS_FAILED}
 * @param output     角色文本产出（DONE 时携带分析/设计/审查结论；FAILED 时携带错误说明；
 *                   编码角色的 START/DONE 无文本产出，为 {@code null}）
 * @author owlzhangfq@gmail.com
 */
public record RoleStageEvent(String role, String type, int index, int total, String status, String output) {

    /** 角色类型：文本规划（需求分析/方案设计），一次性模型调用。 */
    public static final String TYPE_PLAN = "PLAN";
    /** 角色类型：编码实现，委托既有 VibeCoding 沙箱链路产出文件/测试。 */
    public static final String TYPE_CODING = "CODING";
    /** 角色类型：自测审查，对本轮 git diff 一次性模型审查。 */
    public static final String TYPE_REVIEW = "REVIEW";

    /** 阶段开始。 */
    public static final String STATUS_START = "START";
    /** 阶段成功完成。 */
    public static final String STATUS_DONE = "DONE";
    /** 阶段失败（流水随即 fast fail 中断，后续角色不再执行）。 */
    public static final String STATUS_FAILED = "FAILED";

    public static RoleStageEvent start(String role, String type, int index, int total) {
        return new RoleStageEvent(role, type, index, total, STATUS_START, null);
    }

    public static RoleStageEvent done(String role, String type, int index, int total, String output) {
        return new RoleStageEvent(role, type, index, total, STATUS_DONE, output);
    }

    public static RoleStageEvent failed(String role, String type, int index, int total, String output) {
        return new RoleStageEvent(role, type, index, total, STATUS_FAILED, output);
    }
}
