package com.richard.fyoung.customerwork.security.spotlight;

/**
 * 不可信内容的来源类型（间接提示词注入的入口）。
 *
 * <p>"不可信"指的是<b>内容不由本系统的提示词工程控制</b>：知识库文档可能被投毒、MCP 工具由后台
 * 任意配置外部服务、工具返回体可能直接来自第三方 HTTP 响应。这些内容一旦原样拼进模型上下文，
 * 其中夹带的"忽略上面的指令"之类文本对模型而言与系统提示词是同一种东西——这正是间接注入
 * （OWASP LLM01 的主要形态）成立的前提。</p>
 *
 * <p>枚举值的 {@link #getLabel()} 会写进隔离块的 {@code source} 属性，让模型和排查者都能看出
 * 这段内容从哪来。</p>
 * @author owlzhangfq@gmail.com
 */
public enum UntrustedSource {

    /** RAG 知识库检索召回的文档片段。 */
    KNOWLEDGE_BASE("knowledge_base"),

    /** 工具（含 MCP）执行返回的结果文本。 */
    TOOL_RESULT("tool_result");

    private final String label;

    UntrustedSource(String label) {
        this.label = label;
    }

    /** 写进隔离块 {@code source} 属性的标签值。 */
    public String getLabel() {
        return label;
    }
}
