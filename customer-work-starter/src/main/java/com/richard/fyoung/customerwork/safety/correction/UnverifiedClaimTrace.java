package com.richard.fyoung.customerwork.safety.correction;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一轮对话内「调过哪些工具」的记录，用来判断模型的资金结论有没有依据。
 *
 * <p>每次订阅新建一份、随事件流走完即弃：中间件是单例，把这种可变状态放字段
 * 会让并发会话互相串味。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class UnverifiedClaimTrace {

    private final Set<String> calledTools = ConcurrentHashMap.newKeySet();

    public void recordTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            calledTools.add(toolName);
        }
    }

    /** 本轮是否调用过给定清单里的任意一个工具。 */
    public boolean calledAnyOf(Set<String> toolNames) {
        for (String name : toolNames) {
            if (calledTools.contains(name)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> calledTools() {
        return Set.copyOf(calledTools);
    }
}
