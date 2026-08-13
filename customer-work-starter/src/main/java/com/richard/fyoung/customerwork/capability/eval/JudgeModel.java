package com.richard.fyoung.customerwork.capability.eval;

import io.agentscope.core.message.Msg;

/**
 * 评测模型函数式接口（LLM-as-Judge）。
 *
 * <p>抽象出"输入一条 Msg、返回一条 Msg"的同步调用契约，
 * 使 {@link QualityEvalRunner} 不直接依赖框架 {@code Model} 的流式 API，
 * 便于离线 mock 与适配不同模型实现。</p>
 *
 * <p>默认实现由 {@link EvalConfig#judgeModel} 提供（复用主对话模型）；
 * 要换用更强的模型做严格评测，声明自己的 {@code JudgeModel} Bean 覆盖即可。
 * 适配写法见该方法——按 2.0 GA 的 {@code Model} 契约收集流式分片再拼接文本，
 * 与项目内其他同步调模型处（{@code TicketClassifier} 等）保持同一手法。</p>
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface JudgeModel {

    /**
     * 同步对话（用于 Judge 评测：一次输入、一次输出）。
     *
     * @param message 输入消息
     * @return 模型回复消息；失败时返回 null
     */
    Msg chat(Msg message);
}
