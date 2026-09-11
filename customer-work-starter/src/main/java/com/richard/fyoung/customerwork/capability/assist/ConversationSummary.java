package com.richard.fyoung.customerwork.capability.assist;

import java.util.List;

/**
 * 会话总结建议（转人工时给接手坐席的结构化上下文摘要）。
 *
 * <p>由 {@link ConversationSummaryService} 对整段会话历史做一次性 LLM 总结得到；模型不守 JSON 格式时降级为
 * 规则版建议（{@code fromModel=false}）。历史读取失败会显式报错，由转人工增强器隔离失败。</p>
 *
 * @param oneLineSummary   一句话摘要
 * @param userIntent       用户核心意图
 * @param emotion          用户情绪（如 平静/不满/愤怒）
 * @param triedSolutions   已尝试过的方案（可空列表）
 * @param pendingIssues    仍待解决的问题（可空列表）
 * @param suggestedNextStep 建议下一步动作
 * @param suggestedReply   建议话术
 * @param fromModel        true=LLM 生成；false=模型不可用/不守格式时的规则降级结果
 * @param evidence         实际使用的消息摘录、版本与生成时间；兼容构造的旧值可为空
 * @author owlzhangfq@gmail.com
 */
public record ConversationSummary(String oneLineSummary, String userIntent, String emotion,
                                  List<String> triedSolutions, List<String> pendingIssues,
                                  String suggestedNextStep, String suggestedReply, boolean fromModel,
                                  SummaryEvidence evidence) {

    public ConversationSummary {
        triedSolutions = triedSolutions == null ? List.of() : List.copyOf(triedSolutions);
        pendingIssues = pendingIssues == null ? List.of() : List.copyOf(pendingIssues);
    }

    /** 保留既有分类器等调用方的构造契约，依据由摘要服务在整理完成后补齐。 */
    public ConversationSummary(String oneLineSummary, String userIntent, String emotion,
                               List<String> triedSolutions, List<String> pendingIssues,
                               String suggestedNextStep, String suggestedReply, boolean fromModel) {
        this(oneLineSummary, userIntent, emotion, triedSolutions, pendingIssues,
            suggestedNextStep, suggestedReply, fromModel, null);
    }

    /** 绑定本次真实读取的依据，不接受模型提供的来源或生成时间。 */
    public ConversationSummary withEvidence(SummaryEvidence sourceEvidence) {
        return new ConversationSummary(oneLineSummary, userIntent, emotion, triedSolutions,
            pendingIssues, suggestedNextStep, suggestedReply, fromModel, sourceEvidence);
    }
}
