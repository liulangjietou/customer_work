package com.richard.fyoung.customerwork.capability.eval;

import io.agentscope.core.message.Msg;

/**
 * 评测模型函数式接口（LLM-as-Judge）。
 *
 * <p>抽象出"输入一条 Msg、返回一条 Msg"的同步调用契约，
 * 使 {@link QualityEvalRunner} 不直接依赖框架 {@code Model} 的流式 API，
 * 便于离线 mock 与适配不同模型实现。</p>
 *
 * <pre>{@code
 * // 生产适配示例：把 Model 适配为 JudgeModel
 * JudgeModel judge = msg -> model.stream(List.of(msg), List.of(), null)
 *     .reduce(ChatResponse::merge)
 *     .map(resp -> resp.toMsg())
 *     .block();
 * }</pre>
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
