package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import java.util.List;
import java.util.stream.Collectors;

/** 正式 FAQ 和候选快照共用来源格式，评测上下文与发布后的工具返回保持一致。 */
final class KnowledgeRecallFormatter {
    private KnowledgeRecallFormatter() { }

    static String format(List<KnowledgeDO> entries) {
        if (entries.isEmpty()) return KnowledgeBackend.NO_HIT_REPLY;
        return "知识库召回如下：\n" + entries.stream()
            .map(entry -> "· " + entry.getContent() + "（来源：" + entry.getSource() + "）")
            .collect(Collectors.joining("\n"));
    }
}
