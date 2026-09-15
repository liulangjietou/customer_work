package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/** 每次试用独有的工具对象，只能读取本次冻结资源；不接受模型自报租户、用户或版本。 */
public final class AgentDraftReadonlyTools {
    public static final String SEARCH_KNOWLEDGE_TOOL = "trial_search_knowledge";
    public static final String READ_SKILL_TOOL = "trial_read_skill";
    private final AgentDraftTrialResources resources;
    private final FrozenAgentDraft draft;
    private final AgentInvocationIdentity identity;
    private final AtomicReference<Throwable> readFailure = new AtomicReference<>();

    public AgentDraftReadonlyTools(AgentDraftTrialResources resources, FrozenAgentDraft draft,
                                  AgentInvocationIdentity identity) {
        this.resources = resources;
        this.draft = draft;
        this.identity = identity;
    }

    /** 只读检索，权限使用进入试用时冻结的真实登录主体。 */
    @Tool(name = SEARCH_KNOWLEDGE_TOOL, description = "查询本次试用已冻结的知识库，返回可见资料。")
    public Mono<List<KnowledgeNode>> search(@ToolParam(name = "query", description = "检索问题") String query) {
        return Mono.fromCallable(() -> resources.search(draft, query, identity))
            .doOnError(error -> readFailure.compareAndSet(null, error));
    }

    /** Skill 内容是参考资料，任何脚本和文件写入请求均不在工具能力中。 */
    @Tool(name = READ_SKILL_TOOL, description = "读取本次试用 Skill 的 SKILL.md 或 UTF-8 文本附件，不执行脚本。")
    public Mono<String> skill(@ToolParam(name = "code", description = "已绑定的 Skill 编码") String code,
        @ToolParam(name = "path", description = "文本附件路径，读取说明时填 SKILL.md") String path) {
        return Mono.fromCallable(() -> resources.skill(draft, code, path, identity))
            .doOnError(error -> readFailure.compareAndSet(null, error));
    }

    /** 框架可能把工具异常转成消息，资源失败仍不能被一次自然语言回答掩盖。 */
    public void requireSuccessfulReads() {
        if (readFailure.get() != null) throw new IllegalStateException("Trial resource read failed", readFailure.get());
    }
}
