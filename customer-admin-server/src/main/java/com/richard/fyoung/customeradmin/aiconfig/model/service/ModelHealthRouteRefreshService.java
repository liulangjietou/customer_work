package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAgentReference;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.List;

/** 把健康路由 overlay 变化传播到所有直接或间接引用该部署的 Agent。 */
@Component
public class ModelHealthRouteRefreshService {

    private final ModelReferenceAccess modelReferenceAccess;
    private final AgentInstanceCache agentInstanceCache;
    private final CustomerWorkConfigPublisher runtimeConfigPublisher;

    public ModelHealthRouteRefreshService(ModelReferenceAccess modelReferenceAccess,
                                          AgentInstanceCache agentInstanceCache,
                                          CustomerWorkConfigPublisher runtimeConfigPublisher) {
        this.modelReferenceAccess = modelReferenceAccess;
        this.agentInstanceCache = agentInstanceCache;
        this.runtimeConfigPublisher = runtimeConfigPublisher;
    }

    public void refresh(AiModelConfig model) {
        List<ModelAgentReference> references = modelReferenceAccess.findReferences(model);
        for (ModelAgentReference reference : references) {
            TenantContext.runWith(reference.getTenantId(), () -> {
                agentInstanceCache.invalidate(reference.getAgentCode());
                runtimeConfigPublisher.publishHealthOverlayForAgentId(reference.getAgentId());
            });
        }
    }
}
