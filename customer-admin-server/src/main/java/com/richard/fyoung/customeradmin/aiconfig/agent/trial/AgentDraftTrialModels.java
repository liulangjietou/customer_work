package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelRoutePolicy;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelRoutePolicyMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelRoutingPolicyRuntimeAccess;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminModelFailoverProperties;
import com.richard.fyoung.customeradmin.ops.domain.FrozenKnowledgeModel;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeTrialModelService;
import com.richard.fyoung.customeradmin.tenant.TenantSqlConditions;
import com.richard.fyoung.customerwork.core.model.failover.FailoverModel;
import com.richard.fyoung.customerwork.core.model.failover.ModelCircuitBreakerRegistry;
import com.richard.fyoung.customerwork.core.model.routing.PolicyRouteSpec;
import com.richard.fyoung.customerwork.core.model.routing.PolicyRoutingModel;
import com.richard.fyoung.customerwork.infra.config.RuntimeModelRouteMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.model.Model;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/** 冻结并装配草稿所选主备或策略模型；试用熔断记忆不污染正式实例。 */
@Service
public class AgentDraftTrialModels {
    private final KnowledgeTrialModelService models;
    private final ModelRoutingPolicyRuntimeAccess policies;
    private final AiModelRoutePolicyMapper policyMapper;
    private final AdminModelFailoverProperties failover;

    public AgentDraftTrialModels(KnowledgeTrialModelService models, ModelRoutingPolicyRuntimeAccess policies,
                                AiModelRoutePolicyMapper policyMapper, AdminModelFailoverProperties failover) {
        this.models = models;
        this.policies = policies;
        this.policyMapper = policyMapper;
        this.failover = failover;
    }

    /** 非路由模式保留备模型顺序；路由模式保存无凭据规格及所有实际候选。 */
    public Selection freeze(AgentSaveRequest configuration, Long agentId) {
        if (configuration.modelRoutePolicyId() != null) {
            long policyId = configuration.modelRoutePolicyId();
            if (policyMapper.selectOne(new QueryWrapper<AiModelRoutePolicy>().eq("id", policyId)
                .apply(TenantSqlConditions.EXACT_TENANT, TenantContext.require())) == null) {
                throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "试用路由策略不可见");
            }
            var runtime = policies.requireActive(policyId, agentId, "admin");
            // runtime 含密文，必须先映射为明确不含密钥的规格再进入持久化记录。
            var spec = RuntimeModelRouteMapper.toSpec(runtime);
            var ids = spec.rules().stream().map(PolicyRouteSpec.Rule::deploymentId).distinct().toList();
            return new Selection(ids.stream().map(models::freeze).toList(), spec);
        }
        var ids = new LinkedHashSet<Long>();
        ids.add(configuration.modelId());
        if (configuration.backupModelIds() != null) ids.addAll(configuration.backupModelIds());
        return new Selection(ids.stream().map(models::freeze).toList(), null);
    }

    /** 建模再次核对冻结端点和可运行性，凭据仅在内存中解析。 */
    public Model build(FrozenAgentDraft draft) {
        var candidates = new LinkedHashMap<Long, Model>();
        for (var frozen : draft.models()) candidates.put(frozen.deploymentId(), models.build(frozen));
        if (draft.routing() != null) return new PolicyRoutingModel(draft.routing(), candidates);
        if (candidates.size() == 1) return candidates.values().iterator().next();
        var ordered = candidates.entrySet().stream()
            .map(entry -> new FailoverModel.Candidate(entry.getKey(), entry.getValue())).toList();
        // 首分片后失败必须透传，不能把两个模型的回答拼成一次成功试用。
        return new FailoverModel(ordered, new ModelCircuitBreakerRegistry(
            failover.getFailureThreshold(), failover.getOpenDurationSeconds()), false);
    }

    public record Selection(List<FrozenKnowledgeModel> models, PolicyRouteSpec routing) { }
}
