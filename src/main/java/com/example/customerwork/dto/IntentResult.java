package com.example.customerwork.dto;

/**
 * 意图识别的结构化输出（对应深度解析 3.3"结构化输出"）。
 *
 * <p>通过 ReActAgent 的 {@code call(msg, IntentResult.class)} 让模型严格按本 Schema 输出，
 * 终结"请按 JSON 格式输出"的提示词玄学，业务代码直接拿到强类型对象对接下游 Java 系统。</p>
 *
 * @param intent  意图分类：consult（咨询）/ order（订单查询）/ refund（售后退款）/ complaint（投诉）/ other
 * @param orderId 若用户消息中包含订单号则回填，否则为空字符串
 * @param urgent  是否为高优先级 / 需尽快人工介入
 * @param summary 一句话概括用户诉求
 * @author owlzhangfq@gmail.com
 */
public record IntentResult(String intent, String orderId, boolean urgent, String summary) {
}
