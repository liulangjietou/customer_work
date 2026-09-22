package com.richard.fyoung.customerwork.capability.typesafe;

import java.util.Map;
import java.util.Optional;

/**
 * 一次 System One 调用的解析结果。
 *
 * @param model     实际服务的模型版本（如 jev-1.13.0），用于展示与排查
 * @param answers   问题编号 → 答案；只包含解析成功的问题
 * @param latencyMs 端到端耗时（毫秒）
 */
public record SystemOneResult(String model, Map<String, SystemOneAnswer> answers, long latencyMs) {

    public Optional<SystemOneAnswer.Choice> choice(String questionId) {
        return typed(questionId, SystemOneAnswer.Choice.class);
    }

    public Optional<SystemOneAnswer.Score> score(String questionId) {
        return typed(questionId, SystemOneAnswer.Score.class);
    }

    public Optional<SystemOneAnswer.Noul> noul(String questionId) {
        return typed(questionId, SystemOneAnswer.Noul.class);
    }

    private <T extends SystemOneAnswer> Optional<T> typed(String questionId, Class<T> type) {
        SystemOneAnswer answer = answers.get(questionId);
        return type.isInstance(answer) ? Optional.of(type.cast(answer)) : Optional.empty();
    }
}
