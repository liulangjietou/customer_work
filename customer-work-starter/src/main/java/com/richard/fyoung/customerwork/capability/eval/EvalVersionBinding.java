package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.infra.config.properties.McpProperties;
import com.richard.fyoung.customerwork.infra.config.properties.ModelProperties;
import com.richard.fyoung.customerwork.infra.config.properties.RagProperties;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Comparator;

/**
 * 一次评测的可复现版本绑定。
 *
 * <p>指纹只包含影响推理结果的非密钥字段。API Key、MCP headers 等凭据既不能进入评测事实，
 * 轮换凭据本身也不应被误判为模型/工具能力变更。知识库默认绑定检索配置与外部数据集标识；
 * 下游若有独立知识版本号，可覆盖 {@link EvalArtifactVersionProvider} 提供权威版本。</p>
 */
public record EvalVersionBinding(
    String datasetVersion,
    String datasetFingerprint,
    String modelVersion,
    String promptVersion,
    String agentVersion,
    String knowledgeBaseVersion,
    String toolVersion,
    String judgeVersion,
    String rubricVersion
) {

    private static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    public EvalVersionBinding {
        datasetVersion = normalize(datasetVersion);
        datasetFingerprint = normalize(datasetFingerprint);
        modelVersion = normalize(modelVersion);
        promptVersion = normalize(promptVersion);
        agentVersion = normalize(agentVersion);
        knowledgeBaseVersion = normalize(knowledgeBaseVersion);
        toolVersion = normalize(toolVersion);
        judgeVersion = normalize(judgeVersion);
        rubricVersion = normalize(rubricVersion);
    }

    /** 历史运行只有提示词指纹，其余维度保持空，门禁会明确识别为不完整绑定。 */
    public static EvalVersionBinding legacy(String promptVersion) {
        return new EvalVersionBinding("", "", "", promptVersion, "", "", "", "", "");
    }

    /** 采集客服端当前真实生效配置的版本；凭据字段刻意排除。 */
    public static EvalVersionBinding fromProperties(CustomerWorkProperties properties,
                                                    EvalType type,
                                                    String promptVersion,
                                                    String judgeVersion,
                                                    String rubricVersion) {
        Objects.requireNonNull(properties, "properties");
        return new EvalVersionBinding(
            "",
            "",
            modelVersion(properties.getModel()),
            promptVersion,
            EvalFingerprint.of("agent-v1", properties.getAgent().getMaxIters()),
            knowledgeVersion(properties.getRag()),
            toolVersion(properties.getMcp()),
            type == EvalType.QUALITY ? judgeVersion : NOT_APPLICABLE,
            rubricVersion);
    }

    /**
     * 从 admin 已组装的发布候选中提取可比版本。
     *
     * <p>发布载荷不包含知识库、Judge、rubric 与评测集，这些维度留空；
     * {@link #matchesCandidate(EvalVersionBinding)} 只比较候选实际控制的非空维度。</p>
     */
    public static EvalVersionBinding fromRuntimeConfig(CustomerWorkRuntimeConfig runtime) {
        Objects.requireNonNull(runtime, "runtime");
        CustomerWorkRuntimeConfig.Model model = runtime.getModel();
        CustomerWorkRuntimeConfig.Fallback fallback = runtime.getFallback();
        String modelVersion = EvalFingerprint.of(
            "runtime-model-v1",
            model == null ? "" : model.getProvider(),
            model == null ? "" : model.getName(),
            model == null ? "" : model.getBaseUrl(),
            fallback != null && fallback.isEnabled(),
            fallback != null && fallback.isEnabled() ? fallback.getProvider() : "",
            fallback != null && fallback.isEnabled() ? fallback.getName() : "",
            fallback != null && fallback.isEnabled() ? fallback.getBaseUrl() : "");
        if (runtime.getRoutingPolicy() != null) {
            modelVersion = EvalFingerprint.of(
                "runtime-model-policy-v1", modelVersion, runtimeRouteVersion(runtime.getRoutingPolicy()));
        }
        if (runtime.getOnlineExperiment() != null) {
            modelVersion = EvalFingerprint.of(
                "runtime-model-experiment-v1", modelVersion,
                runtimeExperimentVersion(runtime.getOnlineExperiment()));
        }
        String promptVersion = com.richard.fyoung.customerwork.capability.prompt.PromptVersion
            .fingerprintOf(runtime.getSystemPrompt());
        Integer maxIters = runtime.getAgent() == null ? null : runtime.getAgent().getMaxIters();
        CustomerWorkRuntimeConfig.RoutingPolicy routing = runtime.getRoutingPolicy();
        CustomerWorkRuntimeConfig.OnlineExperiment experiment = runtime.getOnlineExperiment();
        // 无治理载荷时必须保持与 fromProperties 的 agent-v1 等价，否则存量发布会被门禁误拒。
        String agentVersion = routing == null && experiment == null
            ? EvalFingerprint.of("agent-v1", maxIters)
            : EvalFingerprint.of("agent-v2", maxIters,
                routing == null ? "" : routing.getAgentId(),
                routing == null ? "" : routing.getChannelCode(),
                routing == null ? "" : routing.getPolicyId(),
                routing == null ? "" : routing.getVersionId(),
                experiment == null ? "" : experiment.getExperimentId(),
                experiment == null ? "" : experiment.getRevision(),
                experiment == null ? "" : experiment.getTreatmentBps());
        String toolVersion = runtimeToolVersion(runtime.getMcpServers());
        return new EvalVersionBinding("", "", modelVersion, promptVersion, agentVersion,
            "", toolVersion, "", "");
    }

    public EvalVersionBinding withDataset(EvalDatasetSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new EvalVersionBinding(snapshot.versionId(), snapshot.contentHash(), modelVersion,
            promptVersion, agentVersion, knowledgeBaseVersion, toolVersion, judgeVersion, rubricVersion);
    }

    /** 新评测运行的九个版本维度都必须有值；历史记录因此不会被误用作发布依据。 */
    @JsonIgnore
    public boolean isComplete() {
        return StringUtils.hasText(datasetVersion)
            && StringUtils.hasText(datasetFingerprint)
            && StringUtils.hasText(modelVersion)
            && StringUtils.hasText(promptVersion)
            && StringUtils.hasText(agentVersion)
            && StringUtils.hasText(knowledgeBaseVersion)
            && StringUtils.hasText(toolVersion)
            && StringUtils.hasText(judgeVersion)
            && StringUtils.hasText(rubricVersion);
    }

    /** 仅比较发布候选实际携带、会被本次发布改变的维度。 */
    public boolean matchesCandidate(EvalVersionBinding candidate) {
        if (candidate == null) {
            return false;
        }
        return matchesIfSpecified(modelVersion, candidate.modelVersion)
            && matchesIfSpecified(promptVersion, candidate.promptVersion)
            && matchesIfSpecified(agentVersion, candidate.agentVersion)
            && matchesIfSpecified(knowledgeBaseVersion, candidate.knowledgeBaseVersion)
            && matchesIfSpecified(toolVersion, candidate.toolVersion)
            && matchesIfSpecified(judgeVersion, candidate.judgeVersion)
            && matchesIfSpecified(rubricVersion, candidate.rubricVersion);
    }

    private static boolean matchesIfSpecified(String actual, String expected) {
        return !StringUtils.hasText(expected) || Objects.equals(actual, expected);
    }

    private static String modelVersion(ModelProperties model) {
        ModelProperties.Fallback fallback = model.getFallback();
        return EvalFingerprint.of(
            "runtime-model-v1", model.getProvider(), model.getName(), model.getBaseUrl(),
            fallback.isEnabled(),
            fallback.isEnabled() ? fallback.getProvider() : "",
            fallback.isEnabled() ? fallback.getName() : "",
            fallback.isEnabled() ? fallback.getBaseUrl() : "");
    }

    private static String knowledgeVersion(RagProperties rag) {
        return EvalFingerprint.of(
            "knowledge-v1", rag.isEnabled(), rag.getProvider(), rag.getTopK(),
            rag.getSimple().getDimensions(),
            rag.getBailian().getWorkspaceId(), rag.getBailian().getIndexId(),
            rag.getBailian().getEndpoint(), rag.getBailian().isEnableReranking(),
            rag.getDify().getApiBaseUrl(), rag.getDify().getDatasetId(), rag.getDify().isEnableRerank());
    }

    private static String toolVersion(McpProperties mcp) {
        StringBuilder canonical = new StringBuilder();
        List<McpProperties.Server> servers = mcp.getServers() == null ? List.of() : mcp.getServers();
        for (McpProperties.Server server : servers) {
            canonical.append(server.getName()).append('\n')
                .append(server.getUrl()).append('\n')
                .append(server.getTransport()).append('\n');
        }
        return EvalFingerprint.of("tool-v1", mcp.isEnabled(), canonical);
    }

    private static String runtimeToolVersion(List<CustomerWorkRuntimeConfig.McpServer> servers) {
        StringBuilder canonical = new StringBuilder();
        List<CustomerWorkRuntimeConfig.McpServer> safeServers = servers == null ? List.of() : servers;
        for (CustomerWorkRuntimeConfig.McpServer server : safeServers) {
            canonical.append(server.getName()).append('\n')
                .append(server.getUrl()).append('\n')
                .append(server.getTransport()).append('\n');
        }
        return EvalFingerprint.of("tool-v1", !safeServers.isEmpty(), canonical);
    }

    /** 路由指纹排除 apiKeyCipher，但覆盖策略版本、规则与全部候选端点修订。 */
    private static String runtimeRouteVersion(CustomerWorkRuntimeConfig.RoutingPolicy policy) {
        StringBuilder canonical = new StringBuilder()
            .append(policy.getPolicyId()).append('\n')
            .append(policy.getVersionId()).append('\n')
            .append(policy.getVersionNo()).append('\n')
            .append(policy.getPolicyContentHash()).append('\n');
        List<CustomerWorkRuntimeConfig.RoutingDeployment> deployments = policy.getDeployments() == null
            ? List.of() : policy.getDeployments().stream()
                .sorted(Comparator.comparing(CustomerWorkRuntimeConfig.RoutingDeployment::getDeploymentId))
                .toList();
        for (CustomerWorkRuntimeConfig.RoutingDeployment deployment : deployments) {
            canonical.append(deployment.getDeploymentId()).append('|')
                .append(deployment.getProvider()).append('|')
                .append(deployment.getName()).append('|')
                .append(deployment.getBaseUrl()).append('|')
                .append(deployment.getEndpointRevision()).append('\n');
        }
        List<CustomerWorkRuntimeConfig.RoutingRule> rules = policy.getRules() == null ? List.of()
            : policy.getRules().stream()
                .sorted(Comparator.comparing(CustomerWorkRuntimeConfig.RoutingRule::getPriority)
                    .thenComparing(CustomerWorkRuntimeConfig.RoutingRule::getRuleId))
                .toList();
        for (CustomerWorkRuntimeConfig.RoutingRule rule : rules) {
            canonical.append(rule.getRuleId()).append('|')
                .append(rule.getPurpose()).append('|')
                .append(rule.getDeploymentId()).append('|')
                .append(rule.getPriority()).append('|')
                .append(rule.getCondition()).append('\n');
        }
        return EvalFingerprint.of("runtime-route-v1", canonical);
    }

    /** 实验指纹排除双臂 apiKeyCipher，但覆盖分桶与端点修订，保证曝光可复现。 */
    private static String runtimeExperimentVersion(
        CustomerWorkRuntimeConfig.OnlineExperiment experiment) {
        StringBuilder canonical = new StringBuilder()
            .append(experiment.getExperimentId()).append('\n')
            .append(experiment.getRevision()).append('\n')
            .append(experiment.getAssignmentSalt()).append('\n')
            .append(experiment.getTreatmentBps()).append('\n')
            .append(experiment.getExpiresAtEpochMs()).append('\n');
        appendExperimentArm(canonical, experiment.getControl());
        appendExperimentArm(canonical, experiment.getTreatment());
        return EvalFingerprint.of("runtime-experiment-v1", canonical);
    }

    private static void appendExperimentArm(StringBuilder canonical,
                                            CustomerWorkRuntimeConfig.ExperimentArm arm) {
        if (arm == null) {
            canonical.append("missing-arm\n");
            return;
        }
        canonical.append(arm.getArm()).append('|')
            .append(arm.getDeploymentId()).append('|')
            .append(arm.getProvider()).append('|')
            .append(arm.getName()).append('|')
            .append(arm.getBaseUrl()).append('|')
            .append(arm.getEndpointRevision()).append('\n');
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
