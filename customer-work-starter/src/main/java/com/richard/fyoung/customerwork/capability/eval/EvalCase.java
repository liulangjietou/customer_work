package com.richard.fyoung.customerwork.capability.eval;

/**
 * 意图评测用例（借鉴 AliGo 测评系统的"用例沉淀复用"）。
 *
 * @param id             用例编号
 * @param input          用户输入
 * @param expectedIntent 期望意图（refund/order/complaint/consult）；{@code null} 表示该用例语义模糊，
 *                       期望规则快车道<b>不</b>命中（应交 LLM 慢车道）
 * @param category       归类标签，用于按类目统计
 * @author owlzhangfq@gmail.com
 */
public record EvalCase(String id, String input, String expectedIntent, String category) {
}
