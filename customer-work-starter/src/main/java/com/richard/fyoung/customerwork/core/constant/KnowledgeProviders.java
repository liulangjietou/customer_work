package com.richard.fyoung.customerwork.core.constant;

import java.util.Set;

/**
 * RAG 知识库实现的取值常量。
 *
 * <p><b>为什么要收敛</b>：这套取值此前只以字面量形式散在 {@code KnowledgeProvider#build()} 的
 * switch 分支、{@code RagProperties} 的 javadoc 与各环境 yml 里，三处并不一致——javadoc 声称支持
 * {@code ragflow} 与 {@code haystack}，而 switch 里没有对应分支，配上去会落进 default。</p>
 *
 * <p><b>default 分支是本项目一个真实的生产隐患</b>：它兜住的不只是 {@code memory}，还包括任何
 * 拼写错误（{@code bailain}）与任何未实现的取值——一律静默降级成内置的 4 条演示文本，
 * 只打一行 info 日志。运营会以为知识库在工作，实际上客服智能体的全部知识就是那 4 句话。
 * 因此取值判定一律走这里的 {@link #isImplemented(String)}，未知值必须显式失败而不是降级。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class KnowledgeProviders {

    /** 内置内存关键词知识库：语料是代码里硬编码的演示文本，<b>仅供本地开发</b>，生产禁用。 */
    public static final String MEMORY = "memory";

    /** 百炼 Embedding + 内存向量库：真实语义检索，文档需运维侧灌入。 */
    public static final String SIMPLE = "simple";

    /** 百炼企业知识库。 */
    public static final String BAILIAN = "bailian";

    /** Dify 知识库。 */
    public static final String DIFY = "dify";

    /**
     * 受管知识库：客服端直连后台维护的那套企业知识库（{@code cw_knowledge_chunk}）。
     *
     * <p>此前客服端与后台是两套互不相通的知识栈——运营在后台做的版本管理、ACL、新鲜度门禁
     * 对线上对话零影响。这个取值是把两者接上的那一条。</p>
     */
    public static final String MANAGED = "managed";

    /** 已实现的全部取值。不在这里面的一律视为配置错误。 */
    public static final Set<String> IMPLEMENTED = Set.of(MEMORY, SIMPLE, BAILIAN, DIFY, MANAGED);

    /**
     * 允许在生产使用的取值：{@link #MEMORY} 刻意排除在外。
     *
     * <p>它的语料是硬编码演示文本，上生产等于让智能体只认那 4 条政策。这与
     * {@code scripts/clear-demo-data.sh} 要清的数据库演示数据是同一类问题的两面，
     * 区别在于代码里的这份没有任何脚本能清理，只能靠配置与门禁挡住。</p>
     */
    public static final Set<String> PRODUCTION_ALLOWED = Set.of(SIMPLE, BAILIAN, DIFY, MANAGED);

    private KnowledgeProviders() {
    }

    /** 归一化：null 与空白按 {@link #MEMORY} 处理，与 {@code KnowledgeProvider#build()} 的默认一致。 */
    public static String normalize(String provider) {
        return provider == null || provider.isBlank() ? MEMORY : provider.trim().toLowerCase();
    }

    /** 是否为已实现的取值（大小写不敏感）。 */
    public static boolean isImplemented(String provider) {
        return IMPLEMENTED.contains(normalize(provider));
    }

    /** 是否允许用于生产（大小写不敏感）。 */
    public static boolean isProductionAllowed(String provider) {
        return PRODUCTION_ALLOWED.contains(normalize(provider));
    }

    /** 是否为内置演示知识库（大小写不敏感）。 */
    public static boolean isMemory(String provider) {
        return MEMORY.equals(normalize(provider));
    }
}
