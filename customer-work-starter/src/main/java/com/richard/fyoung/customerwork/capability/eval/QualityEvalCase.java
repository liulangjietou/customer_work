package com.richard.fyoung.customerwork.capability.eval;

/**
 * 回复质量评测用例（LLM-as-Judge）。
 *
 * @param id        用例编号
 * @param input     用户输入
 * @param expected   期望的回复要点（供 LLM Judge 参考评分）
 * @param category   归类标签
 * @author owlzhangfq@gmail.com
 */
public record QualityEvalCase(String id, String input, String expected, String category) {
}
