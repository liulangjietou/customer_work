package com.richard.fyoung.customerwork.assist;

/**
 * 坐席辅助建议（给人工坐席的实时提示）。
 *
 * @param suggestedReply  建议话术
 * @param knowledgeHint   相关知识/政策提示
 * @param recommendedTool 建议调用的业务工具名
 * @author owlzhangfq@gmail.com
 */
public record AssistSuggestion(String suggestedReply, String knowledgeHint, String recommendedTool) {
}
