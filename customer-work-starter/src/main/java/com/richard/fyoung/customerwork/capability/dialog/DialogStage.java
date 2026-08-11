package com.richard.fyoung.customerwork.capability.dialog;

/**
 * 对话阶段（借鉴阿里商旅 AliGo：把线性大 Prompt 改为"状态机式动态组装"，按阶段聚焦当前主链路）。
 *
 * <p>每个阶段携带一段聚焦指令（{@link #promptFragment()}），由 {@code DialogStageMiddleware} 在
 * {@code onSystemPrompt} 段按会话当前阶段动态追加，而非把全量规则塞进一个静态系统提示词。</p>
 * @author owlzhangfq@gmail.com
 */
public enum DialogStage {

    /** 接待识别：理解意图、必要时澄清，先不急于调工具。 */
    GREETING("【接待识别】阶段：先理解用户意图，意图不清时主动澄清，暂不调用业务工具。"),

    /** 信息收集：聚焦收齐办理所需关键信息，一次只问最关键的一项。 */
    COLLECTING("【信息收集】阶段：聚焦收集办理本次诉求所需的关键信息（如订单号、原因），缺什么问什么，一次只问最关键的一项。"),

    /** 业务处理：调用工具办理，如实回报，涉资金只生成待人工确认工单。 */
    PROCESSING("【业务处理】阶段：调用对应工具完成查询/办理并如实回报结果；涉及资金只生成待人工确认工单，绝不承诺已打款。"),

    /** 确认收尾：复述结果与后续动作，确认是否还有其它问题。 */
    CONFIRMING("【确认收尾】阶段：向用户复述处理结果与后续动作，并确认是否还有其它问题。"),

    /** 已转人工：不再自行办理，引导用户等待坐席。 */
    ESCALATED("【已转人工】阶段：不要再自行办理业务，安抚并引导用户等待人工坐席接入。");

    private final String promptFragment;

    DialogStage(String promptFragment) {
        this.promptFragment = promptFragment;
    }

    /** 该阶段注入系统提示词的聚焦指令片段。 */
    public String promptFragment() {
        return promptFragment;
    }

    /** 主链路下一阶段：GREETING→COLLECTING→PROCESSING→CONFIRMING；CONFIRMING/ESCALATED 为终态自保持。 */
    public DialogStage next() {
        switch (this) {
            case GREETING:
                return COLLECTING;
            case COLLECTING:
                return PROCESSING;
            case PROCESSING:
                return CONFIRMING;
            default:
                return this;
        }
    }
}
