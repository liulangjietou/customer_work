package com.richard.fyoung.customeradmin.workspace.callstats.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelRoutingPolicyRuntimeAccess;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersion;
import com.richard.fyoung.customerwork.data.calllog.AgentCallLineage;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 构建一次调用的 {@link AgentCallMeta}——必须在<b>请求线程的同步段</b>调用（用户名取自 Sa-Token 的
 * ThreadLocal，SSE 的 reactor 回调线程里已拿不到登录上下文，与审计埋点同一约束）。
 *
 * <p>requestId 生成 UUID（admin 无全链路 requestId 机制）；username 取当前登录用户账号（未登录/无上下文
 * 兜底 null，中间件会回落 ctx.userId）；agentName 取 {@code ai_agent.agent_name}（查不到回落 agentCode）；
 * sessionType 由调用方按渠道传入（对话=CHAT，VibeCoding=VIBE_CODING）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentCallMetaFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentCallMetaFactory.class);

    private final AiAgentMapper agentMapper;
    private final ModelConfigAccess modelConfigAccess;
    private final ModelRoutingPolicyRuntimeAccess routingPolicyRuntimeAccess;
    private final AgentArtifactVersionResolver artifactVersionResolver;

    public AgentCallMetaFactory(AiAgentMapper agentMapper) {
        this(agentMapper, null, null, null);
    }

    public AgentCallMetaFactory(AiAgentMapper agentMapper, ModelConfigAccess modelConfigAccess) {
        this(agentMapper, modelConfigAccess, null, null);
    }

    public AgentCallMetaFactory(AiAgentMapper agentMapper,
                                ModelConfigAccess modelConfigAccess,
                                ModelRoutingPolicyRuntimeAccess routingPolicyRuntimeAccess) {
        this(agentMapper, modelConfigAccess, routingPolicyRuntimeAccess, null);
    }

    @Autowired
    public AgentCallMetaFactory(AiAgentMapper agentMapper,
                                ModelConfigAccess modelConfigAccess,
                                ModelRoutingPolicyRuntimeAccess routingPolicyRuntimeAccess,
                                AgentArtifactVersionResolver artifactVersionResolver) {
        this.agentMapper = agentMapper;
        this.modelConfigAccess = modelConfigAccess;
        this.routingPolicyRuntimeAccess = routingPolicyRuntimeAccess;
        this.artifactVersionResolver = artifactVersionResolver;
    }

    /** 构建调用元数据。{@code question} 为用户原始输入（VibeCoding 传原始需求而非注入路径指引后的富文本）。 */
    public AgentCallMeta build(String agentCode, AgentCallSessionType sessionType, String question) {
        AiAgent agent = resolveAgent(agentCode);
        return new AgentCallMeta(UUID.randomUUID().toString(), resolveUsername(), agentCode,
            resolveAgentName(agent, agentCode), sessionType, question, resolveLineage(agent));
    }

    /** 供重放 diff 读取当前制品版本；不创建 requestId，也不读取登录 ThreadLocal。 */
    public AgentCallLineage currentLineage(String agentCode) {
        return resolveLineage(resolveAgent(agentCode));
    }

    /** 当前登录用户账号；未登录/无 Sa-Token 上下文（如单测/开放 API）时返回 null，不阻断对话。 */
    private String resolveUsername() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getTokenSession().getString("username");
            }
        } catch (Exception e) {
            log.error("resolve call meta username failed, code={}", "CALLSTATS-META-USER-FAIL", e);
        }
        return null;
    }

    private AiAgent resolveAgent(String agentCode) {
        try {
            return agentMapper.selectOne(
                new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, agentCode));
        } catch (Exception e) {
            log.error("resolve call meta agent failed, code={}, agentCode={}",
                "CALLSTATS-META-AGENT-FAIL", agentCode, e);
        }
        return null;
    }

    /** 智能体展示名（ai_agent.agent_name）；查不到或异常回落 agentCode。 */
    private String resolveAgentName(AiAgent agent, String agentCode) {
        if (agent != null && StringUtils.hasText(agent.getAgentName())) {
            return agent.getAgentName();
        }
        return agentCode;
    }

    /**
     * 冻结 Admin 实际运行制品版本。只纳入会改变推理行为的配置，密钥与 SecretRef 永不进入指纹。
     * 知识库、工具关系尚无不可变快照，保持空值而不伪造可复现性。
     */
    private AgentCallLineage resolveLineage(AiAgent agent) {
        if (agent == null) {
            return AgentCallLineage.empty();
        }
        String modelVersion = resolveModelVersion(agent.getModelId());
        String routeVersion = resolveRouteVersion(agent);
        if (StringUtils.hasText(routeVersion)) {
            modelVersion = EvalFingerprint.of("admin-model-routing-v1", modelVersion, routeVersion);
        }
        String promptVersion = PromptVersion.fingerprintOf(
            AdminAgentInstanceFactory.effectiveSystemPrompt(agent));
        String agentVersion = EvalFingerprint.of(
            "admin-agent-v1",
            agent.getId(),
            agent.getAgentCode(),
            agent.getModelId(),
            agent.getModelRoutePolicyId(),
            agent.getCapabilities(),
            agent.getMaxIters(),
            agent.getToolTimeoutSeconds(),
            agent.getToolMaxAttempts(),
            agent.getCompressTriggerMsgs(),
            agent.getCompressKeepMsgs());
        AgentArtifactVersionResolver.ArtifactVersions artifacts = resolveArtifacts(agent.getId());
        EvalVersionBinding binding = new EvalVersionBinding(
            "", "", modelVersion, promptVersion, agentVersion,
            artifacts.knowledgeBaseVersion(), artifacts.toolVersion(), "", "");
        return new AgentCallLineage("", "", "", binding);
    }

    private AgentArtifactVersionResolver.ArtifactVersions resolveArtifacts(Long agentId) {
        if (artifactVersionResolver == null || agentId == null) {
            return new AgentArtifactVersionResolver.ArtifactVersions("", "");
        }
        try {
            return artifactVersionResolver.resolve(agentId);
        } catch (Exception e) {
            log.error("resolve call meta artifact lineage failed, code={}, agentId={}",
                "CALLSTATS-META-ARTIFACT-FAIL", agentId, e);
            return new AgentArtifactVersionResolver.ArtifactVersions("", "");
        }
    }

    private String resolveRouteVersion(AiAgent agent) {
        if (agent.getModelRoutePolicyId() == null || routingPolicyRuntimeAccess == null) {
            return "";
        }
        try {
            CustomerWorkRuntimeConfig.RoutingPolicy policy = routingPolicyRuntimeAccess.requireActive(
                agent.getModelRoutePolicyId(), agent.getId(), "admin");
            StringBuilder canonical = new StringBuilder()
                .append(policy.getPolicyId()).append('\n')
                .append(policy.getVersionId()).append('\n')
                .append(policy.getVersionNo()).append('\n')
                .append(policy.getPolicyContentHash()).append('\n');
            for (CustomerWorkRuntimeConfig.RoutingDeployment deployment : policy.getDeployments()) {
                canonical.append(deployment.getDeploymentId()).append('|')
                    .append(deployment.getProvider()).append('|')
                    .append(deployment.getName()).append('|')
                    .append(deployment.getBaseUrl()).append('|')
                    .append(deployment.getEndpointRevision()).append('\n');
            }
            for (CustomerWorkRuntimeConfig.RoutingRule rule : policy.getRules()) {
                canonical.append(rule.getRuleId()).append('|')
                    .append(rule.getPurpose()).append('|')
                    .append(rule.getDeploymentId()).append('|')
                    .append(rule.getPriority()).append('|')
                    .append(rule.getCondition()).append('\n');
            }
            return EvalFingerprint.of("model-route-policy-v1", canonical);
        } catch (Exception e) {
            log.error("resolve call meta route lineage failed, code={}, policyId={}",
                "CALLSTATS-META-ROUTE-FAIL", agent.getModelRoutePolicyId(), e);
            return "";
        }
    }

    private String resolveModelVersion(Long modelId) {
        if (modelId == null || modelConfigAccess == null) {
            return "";
        }
        try {
            AiModelConfig model = modelConfigAccess.findVisibleAnyStateById(modelId);
            if (model == null) {
                return "";
            }
            return EvalFingerprint.of(
                "admin-model-deployment-v1",
                model.getId(),
                model.getAssetId(),
                model.getDeploymentCode(),
                model.getProvider(),
                model.getProtocolAdapter(),
                model.getBaseUrl(),
                model.getRegion(),
                model.getEnvironment(),
                model.getModel(),
                model.getEndpointRevision(),
                model.getLifecycleStatus());
        } catch (Exception e) {
            log.error("resolve call meta model lineage failed, code={}, modelId={}",
                "CALLSTATS-META-MODEL-FAIL", modelId, e);
            return "";
        }
    }
}
