package com.richard.fyoung.customerwork.capability.typesafe;

/**
 * Jev 决策点的运行模式：判定之后「执不执行动作」与「要不要把决策发成可展示事件」是两件独立的事。
 *
 * <p><b>C 端必须是 {@link #LIVE}</b>：决策事件是内部实现细节，绝不能出现在用户看得到的事件流里。
 * 与其在每个 C 端出口（WS、流式、AG-UI、渠道）逐个过滤，不如从源头就不产生——
 * 自动装配给 C 端的中间件实例一律 LIVE，只有后台显式构建的实例才会发事件。</p>
 */
public enum JevRunMode {

    /** 线上：执行动作，不发事件。 */
    LIVE(true, false),

    /** 后台影子：只判定并展示「线上会怎么做」，不执行任何动作，也不改变模型看到的任何内容。 */
    SHADOW(false, true),

    /** 后台真执行：执行动作并展示。 */
    LIVE_TRACED(true, true);

    private final boolean act;
    private final boolean emit;

    JevRunMode(boolean act, boolean emit) {
        this.act = act;
        this.emit = emit;
    }

    public boolean act() {
        return act;
    }

    public boolean emit() {
        return emit;
    }
}
