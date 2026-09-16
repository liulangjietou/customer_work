package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.ops.domain.FrozenKnowledgeModel;
import com.richard.fyoung.customerwork.core.model.routing.PolicyRouteSpec;
import java.util.List;

/** 试用的完整输入身份；资源只保存不可变版本引用，不包含连接凭据或正式会话状态。 */
public record FrozenAgentDraft(Long agentId, Long baseRevision, AgentSaveRequest configuration,
                               List<FrozenKnowledgeModel> models, PolicyRouteSpec routing,
                               List<Resource> skills, List<Resource> knowledgeBases,
                               List<SystemTool> systemTools, List<String> restrictions) {
    public FrozenAgentDraft {
        models = List.copyOf(models);
        skills = List.copyOf(skills);
        knowledgeBases = List.copyOf(knowledgeBases);
        systemTools = List.copyOf(systemTools);
        restrictions = List.copyOf(restrictions);
    }

    /** 内容指纹由既有版本服务产生，执行时按同一个资源和版本读取。 */
    public record Resource(long id, long versionId, String name, String contentHash) { }

    /** 代码工具目录全局共享；只有显式批准的纯计算实现可进入试用 Toolkit。 */
    public record SystemTool(long id, String code, boolean enabled) { }
}
