package com.richard.fyoung.customerwork.capability.typesafe;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jev 决策的可展示事件：用框架预留的 {@link CustomEvent}（{@code AgentEventType.CUSTOM}）承载，
 * 不冒用任何既有事件类型，接入层按 {@link #EVENT_NAME} 识别。
 *
 * <p>只有 {@link JevRunMode#emit()} 为真的中间件实例才会产生它，C 端事件流里不存在这类事件。</p>
 *
 * <p>载荷字段即后台前端的展示契约，字段名集中在这里，后台接入层引用同一组常量。</p>
 */
public final class JevDecisionEvent {

    public static final String EVENT_NAME = "typesafe.jev.decision";

    /** 决策点编码。 */
    public static final String KEY_POINT = "point";
    /** 决策点名称（中文，直接展示）。 */
    public static final String KEY_TITLE = "title";
    /** 判定结果描述。 */
    public static final String KEY_VERDICT = "verdict";
    /** 据判定采取的动作；影子模式下是「线上会执行的动作」。 */
    public static final String KEY_ACTION = "action";
    /** 动作是否真的执行了（影子模式为 false）。 */
    public static final String KEY_EXECUTED = "executed";
    /** 置信度或概率，可为空。 */
    public static final String KEY_CONFIDENCE = "confidence";
    public static final String KEY_MODEL = "model";
    public static final String KEY_LATENCY_MS = "latencyMs";
    /** Jev 本轮不可用、已按原逻辑处理。 */
    public static final String KEY_DEGRADED = "degraded";
    /**
     * 运行模式（shadow / live_traced）。光有 executed 分不清「影子所以没执行」和「真执行但这次无需动作」：
     * 前者要让运营看清「线上会这么做、后台没做」，后者就是一次正常放行。
     */
    public static final String KEY_RUN_MODE = "runMode";

    public static final String POINT_TURN = "turn";
    public static final String POINT_ESCALATION = "escalation";
    public static final String POINT_TOOL_SCOPE = "tool_scope";
    public static final String POINT_REFUND_RISK = "refund_risk";
    public static final String POINT_PAYMENT_CLAIM = "payment_claim";
    public static final String POINT_CACHE_GUARD = "cache_guard";

    /** 入站决策（意图 + 情绪共用一次调用）降级时的展示标题。 */
    public static final String TITLE_TURN = "入站决策（意图 + 情绪）";

    // ---------- 决策落点：指标 customerwork.typesafe.decisions 的 result 维度 ----------

    /** Jev 本轮没给出决策，已按原逻辑处理。 */
    public static final String RESULT_DEGRADED = "degraded";
    public static final String RESULT_NARROWED = "narrowed";
    public static final String RESULT_KEPT = "kept";
    public static final String RESULT_HIGH_RISK = "high_risk";
    public static final String RESULT_NORMAL = "normal";
    public static final String RESULT_BLOCKED = "blocked";
    public static final String RESULT_PASSED = "passed";
    public static final String RESULT_REJECTED = "rejected";
    public static final String RESULT_ALLOWED = "allowed";

    private JevDecisionEvent() {
    }

    /**
     * 一次已作出的决策。
     *
     * @param mode       产生该决策的中间件的运行模式
     * @param confidence 置信度或概率；无意义时传 null
     */
    public static CustomEvent decided(JevRunMode mode, String point, String title, String verdict, String action,
                                      boolean executed, Double confidence, String model, long latencyMs) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(KEY_RUN_MODE, mode.name().toLowerCase());
        value.put(KEY_POINT, point);
        value.put(KEY_TITLE, title);
        value.put(KEY_VERDICT, verdict);
        value.put(KEY_ACTION, action);
        value.put(KEY_EXECUTED, executed);
        if (confidence != null) {
            value.put(KEY_CONFIDENCE, confidence);
        }
        value.put(KEY_MODEL, model);
        value.put(KEY_LATENCY_MS, latencyMs);
        value.put(KEY_DEGRADED, false);
        return new CustomEvent(EVENT_NAME, value);
    }

    /** Jev 本轮没给出决策（超时、熔断、调用失败或响应非法），已按原逻辑处理。 */
    public static CustomEvent degraded(String point, String title) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(KEY_POINT, point);
        value.put(KEY_TITLE, title);
        value.put(KEY_VERDICT, "本轮未获得 Jev 决策（超时、熔断或调用失败）");
        value.put(KEY_ACTION, "按原逻辑处理");
        value.put(KEY_EXECUTED, false);
        value.put(KEY_DEGRADED, true);
        return new CustomEvent(EVENT_NAME, value);
    }

    /** 是否为 Jev 决策事件。 */
    public static boolean isDecision(AgentEvent event) {
        return event instanceof CustomEvent custom && EVENT_NAME.equals(custom.getName());
    }
}
