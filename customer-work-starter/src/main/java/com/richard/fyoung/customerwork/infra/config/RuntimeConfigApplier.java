package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.agent.RuntimeAgentAccessState;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import com.richard.fyoung.customerwork.core.model.attribution.AttributedModel;
import com.richard.fyoung.customerwork.core.model.attribution.ModelCallAttribution;
import com.richard.fyoung.customerwork.core.model.attribution.ModelPricingStatus;
import com.richard.fyoung.customerwork.core.model.experiment.OnlineExperimentRoutingModel;
import com.richard.fyoung.customerwork.core.model.routing.PolicyRoutingModel;
import com.richard.fyoung.customerwork.infra.config.properties.McpProperties;
import com.richard.fyoung.customerwork.infra.config.properties.ModelProperties;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 运行时配置热应用器（消费端热更新的落地单元）。
 *
 * <p>把一份 {@link CustomerWorkRuntimeConfig}（API Key 已由上游解密为明文）原子地应用到运行中的 8080：
 * <ol>
 *   <li>回写 {@link CustomerWorkProperties} 的 model / mcp / agent 相关字段（保持配置单一真相）；</li>
 *   <li>{@link MutableDelegatingModel#swap} 用新配置重建的模型链热替换旧链（共享单例，全体消费方即时生效）；</li>
 *   <li>{@link NacosPromptService#updatePrompt} 覆盖系统提示词；</li>
 *   <li>{@link CustomerServiceService#flushHotAgents} 清热缓存，令下一次会话按新提示词/MCP/maxIters 重建 Agent。</li>
 * </ol>
 *
 * <p><b>全有或全无</b>：先做完校验与新链构建（可能抛错的步骤），全部成功后才提交回写与 swap/flush。
 * 任一前置步骤失败即整体放弃、保留旧配置、{@code log.error}，绝不产生"半应用"的中间态。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class RuntimeConfigApplier {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigApplier.class);

    private static final String CODE_APPLY_FAIL = "RUNTIME-CONFIG-APPLY-FAIL";

    private final CustomerWorkProperties properties;
    private final ModelConfig modelConfig;
    private final MutableDelegatingModel mutableModel;
    private final NacosPromptService nacosPromptService;
    private final CustomerServiceService customerServiceService;
    /** 部署级出网信任边界；不能由收到的运行时载荷自行修改。 */
    private final ModelEndpointPolicy endpointPolicy;
    /** 进程启动时的 yml/Java 基线；完整快照显式 null 用它清除旧热覆盖。 */
    private final int baselineMaxIters;
    /** 与全部 Agent 治理中间件共享的生命周期总闸门。 */
    private final RuntimeAgentAccessState runtimeAccessState;

    @Autowired
    public RuntimeConfigApplier(CustomerWorkProperties properties,
                                ModelConfig modelConfig,
                                MutableDelegatingModel mutableModel,
                                NacosPromptService nacosPromptService,
                                CustomerServiceService customerServiceService,
                                RuntimeAgentAccessState runtimeAccessState) {
        this(properties, modelConfig, mutableModel, nacosPromptService, customerServiceService,
            new ModelEndpointPolicy(() -> properties.getModel().getEgress().getAllowedHosts()), runtimeAccessState);
    }

    /** 兼容不装配生命周期热更新的离线单测。 */
    public RuntimeConfigApplier(CustomerWorkProperties properties,
                                ModelConfig modelConfig,
                                MutableDelegatingModel mutableModel,
                                NacosPromptService nacosPromptService,
                                CustomerServiceService customerServiceService) {
        this(properties, modelConfig, mutableModel, nacosPromptService, customerServiceService,
            new ModelEndpointPolicy(() -> properties.getModel().getEgress().getAllowedHosts()),
            RuntimeAgentAccessState.alwaysActive());
    }

    RuntimeConfigApplier(CustomerWorkProperties properties,
                         ModelConfig modelConfig,
                         MutableDelegatingModel mutableModel,
                         NacosPromptService nacosPromptService,
                         CustomerServiceService customerServiceService,
                         ModelEndpointPolicy endpointPolicy) {
        this(properties, modelConfig, mutableModel, nacosPromptService, customerServiceService,
            endpointPolicy, RuntimeAgentAccessState.alwaysActive());
    }

    RuntimeConfigApplier(CustomerWorkProperties properties,
                         ModelConfig modelConfig,
                         MutableDelegatingModel mutableModel,
                         NacosPromptService nacosPromptService,
                         CustomerServiceService customerServiceService,
                         ModelEndpointPolicy endpointPolicy,
                         RuntimeAgentAccessState runtimeAccessState) {
        this.properties = properties;
        this.modelConfig = modelConfig;
        this.mutableModel = mutableModel;
        this.nacosPromptService = nacosPromptService;
        this.customerServiceService = customerServiceService;
        this.endpointPolicy = endpointPolicy;
        this.runtimeAccessState = runtimeAccessState;
        this.baselineMaxIters = properties.getAgent().getMaxIters();
    }

    /**
     * 热应用一份运行时配置。API Key 需已由调用方（{@code NacosRuntimeConfigService}）解密为明文传入。
     *
     * @param dto             运行时配置（apiKeyCipher 字段仅用于校验存在性，实际密钥用下两参）
     * @param primaryApiKey   主模型 API Key 明文；空表示不改动现有主模型密钥
     * @param fallbackApiKey  兜底模型 API Key 明文；空表示不改动现有兜底密钥
     * @return 是否成功应用（失败时旧配置原样保留）
     */
    public synchronized boolean apply(CustomerWorkRuntimeConfig dto, String primaryApiKey, String fallbackApiKey) {
        return apply(dto, primaryApiKey, fallbackApiKey, Map.of());
    }

    /**
     * 热应用 v2 路由配置。routingApiKeys 以 deploymentId 为键，仅在临时建链阶段存在，不写回 properties。
     */
    public synchronized boolean apply(CustomerWorkRuntimeConfig dto,
                                      String primaryApiKey,
                                      String fallbackApiKey,
                                      Map<Long, String> routingApiKeys) {
        return apply(dto, primaryApiKey, fallbackApiKey, routingApiKeys, Map.of());
    }

    /**
     * 热应用 v2 路由与在线实验配置。两组密钥只在临时建链阶段存在，不进入 properties 或日志。
     */
    public synchronized boolean apply(CustomerWorkRuntimeConfig dto,
                                      String primaryApiKey,
                                      String fallbackApiKey,
                                      Map<Long, String> routingApiKeys,
                                      Map<Long, String> experimentApiKeys) {
        try {
            if (dto != null && Boolean.FALSE.equals(dto.getActive())) {
                return applyRevocation(dto);
            }
            validate(dto);
            validateAndNormalizeModelEndpoints(dto);
            // 先在临时配置上构建新链（可能抛错：厂商不支持 / 密钥缺失等），成功后才提交
            ModelProperties staged = stageModelCfg(dto, primaryApiKey, fallbackApiKey);
            Model baseline = Objects.requireNonNull(dto.getRoutingPolicy() == null
                ? modelConfig.buildChain(staged,
                    attribution(dto.getModel().getProvider(), dto.getModel().getDeploymentId(),
                        dto.getModel().getName(), dto.getModel().getPricing()),
                    fallbackAttribution(dto.getFallback()), unavailableBaselineDeployments(dto))
                : buildPolicyChain(dto.getRoutingPolicy(), staged, routingApiKeys),
                "runtime baseline model chain must not be null");
            Model newChain = Objects.requireNonNull(dto.getOnlineExperiment() == null
                ? baseline
                : buildExperimentChain(dto.getOnlineExperiment(), staged, experimentApiKeys, baseline),
                "runtime model chain must not be null");
            PreparedMcp preparedMcp = prepareMcp(dto);
            PreparedAgent preparedAgent = prepareAgent(dto);

            // ---- 提交阶段：以下步骤均为不抛错的赋值/替换，保证全有或全无 ----
            copyModelCfg(staged, properties.getModel());
            applyMcp(preparedMcp);
            applyAgent(preparedAgent);
            nacosPromptService.updatePrompt(dto.getSystemPrompt());
            mutableModel.swap(newChain);
            customerServiceService.flushHotAgents();
            runtimeAccessState.activate(dto.getTargetCode(), dto.getRevision(), dto.getContentHash());

            log.info("[hot-config] runtime config applied, schemaVersion={}, provider={}, model={}, mcpServers={}",
                dto.getSchemaVersion(), staged.getProvider(), staged.getName(),
                dto.getMcpServers() == null ? 0 : dto.getMcpServers().size());
            return true;
        } catch (Exception e) {
            log.error("runtime config apply failed, keep old config, code={}", CODE_APPLY_FAIL, e);
            return false;
        }
    }

    /**
     * 撤销快照不依赖模型/MCP 等已删除资产，只推进总闸门并清理热 Agent。
     * 先关闸再清缓存，保证并发新调用不会在淘汰窗口重新取得旧实例。
     */
    private boolean applyRevocation(CustomerWorkRuntimeConfig dto) {
        if (dto.getSchemaVersion() < 3 || !StringUtils.hasText(dto.getTargetCode())) {
            throw new IllegalArgumentException("runtime revocation targetCode and schemaVersion >= 3 are required");
        }
        runtimeAccessState.revoke(dto.getTargetCode(), dto.getRevision(), dto.getContentHash());
        customerServiceService.flushHotAgents();
        log.info("[hot-config] runtime agent revoked, targetCode={}, revision={}",
            dto.getTargetCode(), dto.getRevision());
        return true;
    }

    /** DTO 基本校验：模型 provider/name 必填（fast fail，防坏配置下发覆盖运行链）。 */
    private void validate(CustomerWorkRuntimeConfig dto) {
        if (dto == null || dto.getModel() == null) {
            throw new IllegalArgumentException("runtime config or model section is null");
        }
        CustomerWorkRuntimeConfig.Model m = dto.getModel();
        if (!StringUtils.hasText(m.getProvider())) {
            throw new IllegalArgumentException("runtime config model.provider is blank");
        }
        if (!StringUtils.hasText(m.getName())) {
            throw new IllegalArgumentException("runtime config model.name is blank");
        }
        if ((dto.getRoutingPolicy() != null || dto.getOnlineExperiment() != null)
            && dto.getSchemaVersion() < 2) {
            throw new IllegalArgumentException(
                "routing policy and online experiment require runtime schemaVersion >= 2");
        }
    }

    /**
     * 在解密后的密钥进入任何 SDK 模型构造前，一次性校验整份快照里的全部自定义模型端点。
     * DTO 是本次 Nacos 消息的私有反序列化对象，可安全原地规范化；任一端点失败则整份配置拒绝。
     */
    private void validateAndNormalizeModelEndpoints(CustomerWorkRuntimeConfig dto) {
        dto.getModel().setBaseUrl(normalizeOptionalEndpoint(dto.getModel().getBaseUrl()));
        if (dto.getFallback() != null) {
            dto.getFallback().setBaseUrl(normalizeOptionalEndpoint(dto.getFallback().getBaseUrl()));
        }
        if (dto.getRoutingPolicy() != null && !CollectionUtils.isEmpty(dto.getRoutingPolicy().getDeployments())) {
            for (CustomerWorkRuntimeConfig.RoutingDeployment deployment
                : dto.getRoutingPolicy().getDeployments()) {
                if (deployment != null) {
                    deployment.setBaseUrl(normalizeOptionalEndpoint(deployment.getBaseUrl()));
                }
            }
        }
        if (dto.getOnlineExperiment() != null) {
            normalizeExperimentArm(dto.getOnlineExperiment().getControl());
            normalizeExperimentArm(dto.getOnlineExperiment().getTreatment());
        }
    }

    private void normalizeExperimentArm(CustomerWorkRuntimeConfig.ExperimentArm arm) {
        if (arm != null) {
            arm.setBaseUrl(normalizeOptionalEndpoint(arm.getBaseUrl()));
        }
    }

    private String normalizeOptionalEndpoint(String baseUrl) {
        return StringUtils.hasText(baseUrl)
            ? endpointPolicy.validateAndNormalizeBaseUrl(baseUrl)
            : baseUrl;
    }

    private Model buildExperimentChain(CustomerWorkRuntimeConfig.OnlineExperiment experiment,
                                       ModelProperties staged,
                                       Map<Long, String> experimentApiKeys,
                                       Model baseline) {
        if (experiment.getControl() == null || experiment.getTreatment() == null) {
            throw new IllegalArgumentException("online experiment arms are incomplete");
        }
        Model control = buildExperimentArm(experiment.getControl(), staged, experimentApiKeys);
        Model treatment = buildExperimentArm(experiment.getTreatment(), staged, experimentApiKeys);
        return new OnlineExperimentRoutingModel(RuntimeOnlineExperimentMapper.toSpec(experiment),
            baseline, control, treatment, routingAvailable(experiment.getControl().getHealth()),
            routingAvailable(experiment.getTreatment().getHealth()));
    }

    private Model buildExperimentArm(CustomerWorkRuntimeConfig.ExperimentArm arm,
                                     ModelProperties staged,
                                     Map<Long, String> experimentApiKeys) {
        if (arm.getDeploymentId() == null || !StringUtils.hasText(arm.getProvider())
            || !StringUtils.hasText(arm.getName())) {
            throw new IllegalArgumentException("online experiment arm fields are incomplete");
        }
        String key = experimentApiKeys == null ? null : experimentApiKeys.get(arm.getDeploymentId());
        if (!ModelProviders.OLLAMA.equalsIgnoreCase(arm.getProvider()) && !StringUtils.hasText(key)) {
            throw new IllegalArgumentException(
                "online experiment deployment API key is missing: " + arm.getDeploymentId());
        }
        Model candidate = modelConfig.buildByProvider(
            arm.getProvider(), arm.getName(), key, arm.getBaseUrl(), staged);
        if (staged.getRetry().isEnabled()) {
            candidate = new ResilientChatModel(candidate, staged.getRetry().getMaxAttempts(),
                staged.getRetry().getBackoffMs());
        }
        return new AttributedModel(candidate,
            attribution(arm.getProvider(), arm.getDeploymentId(), arm.getName(), arm.getPricing()));
    }

    private Model buildPolicyChain(CustomerWorkRuntimeConfig.RoutingPolicy policy,
                                   ModelProperties staged,
                                   Map<Long, String> routingApiKeys) {
        if (CollectionUtils.isEmpty(policy.getDeployments())) {
            throw new IllegalArgumentException("routing policy deployments are empty");
        }
        Map<Long, Model> candidates = new LinkedHashMap<>();
        for (CustomerWorkRuntimeConfig.RoutingDeployment deployment : policy.getDeployments()) {
            if (deployment == null || deployment.getDeploymentId() == null
                || !StringUtils.hasText(deployment.getProvider())
                || !StringUtils.hasText(deployment.getName())) {
                throw new IllegalArgumentException("routing deployment fields are incomplete");
            }
            String key = routingApiKeys == null ? null : routingApiKeys.get(deployment.getDeploymentId());
            if (!ModelProviders.OLLAMA.equalsIgnoreCase(deployment.getProvider()) && !StringUtils.hasText(key)) {
                throw new IllegalArgumentException(
                    "routing deployment API key is missing: " + deployment.getDeploymentId());
            }
            Model candidate = modelConfig.buildByProvider(deployment.getProvider(), deployment.getName(),
                key, deployment.getBaseUrl(), staged);
            if (staged.getRetry().isEnabled()) {
                candidate = new ResilientChatModel(candidate, staged.getRetry().getMaxAttempts(),
                    staged.getRetry().getBackoffMs());
            }
            candidate = new AttributedModel(candidate,
                attribution(deployment.getProvider(), deployment.getDeploymentId(),
                    deployment.getName(), deployment.getPricing()));
            if (candidates.putIfAbsent(deployment.getDeploymentId(), candidate) != null) {
                throw new IllegalArgumentException(
                    "duplicate routing deployment: " + deployment.getDeploymentId());
            }
        }
        return new PolicyRoutingModel(RuntimeModelRouteMapper.toSpec(policy), candidates);
    }

    private ModelCallAttribution fallbackAttribution(CustomerWorkRuntimeConfig.Fallback fallback) {
        if (fallback == null) {
            return ModelCallAttribution.unpriced(null, null, null);
        }
        return attribution(fallback.getProvider(), fallback.getDeploymentId(),
            fallback.getName(), fallback.getPricing());
    }

    private Set<Long> unavailableBaselineDeployments(CustomerWorkRuntimeConfig dto) {
        Set<Long> unavailable = new LinkedHashSet<>();
        if (!routingAvailable(dto.getModel().getHealth()) && dto.getModel().getDeploymentId() != null) {
            unavailable.add(dto.getModel().getDeploymentId());
        }
        if (dto.getFallback() != null && !routingAvailable(dto.getFallback().getHealth())
            && dto.getFallback().getDeploymentId() != null) {
            unavailable.add(dto.getFallback().getDeploymentId());
        }
        return Set.copyOf(unavailable);
    }

    private boolean routingAvailable(CustomerWorkRuntimeConfig.HealthOverlay health) {
        return health == null || health.isRoutingAvailable();
    }

    private ModelCallAttribution attribution(String provider, Long deploymentId, String model,
                                             CustomerWorkRuntimeConfig.Pricing pricing) {
        if (pricing == null || !ModelPricingStatus.PRICED.name().equals(pricing.getStatus())
            || pricing.getPriceId() == null) {
            return ModelCallAttribution.unpriced(provider, deploymentId, model);
        }
        return new ModelCallAttribution(provider, deploymentId, model, pricing.getPriceId(),
            pricing.getCurrency(), pricing.getInputUnitPrice(), pricing.getOutputUnitPrice(),
            pricing.getCachedUnitPrice(), ModelPricingStatus.PRICED);
    }

    /** 用当前配置打底、以 DTO 覆盖，产出一份用于构建新链的临时模型配置（不改动 properties）。 */
    private ModelProperties stageModelCfg(CustomerWorkRuntimeConfig dto,
                                                       String primaryApiKey, String fallbackApiKey) {
        ModelProperties base = properties.getModel();
        ModelProperties staged = new ModelProperties();
        // 打底：先复制当前生效值，DTO 未提供的字段沿用现状
        copyModelCfg(base, staged);

        CustomerWorkRuntimeConfig.Model m = dto.getModel();
        staged.setProvider(m.getProvider());
        staged.setName(m.getName());
        staged.setBaseUrl(m.getBaseUrl());
        if (StringUtils.hasText(primaryApiKey)) {
            staged.setApiKey(primaryApiKey);
        }
        if (m.getTemperature() != null) {
            staged.setTemperature(m.getTemperature());
        }
        if (m.getMaxTokens() != null) {
            staged.setMaxTokens(m.getMaxTokens());
        }
        if (m.getTopP() != null) {
            staged.setTopP(m.getTopP());
        }
        if (m.getStream() != null) {
            staged.setStream(m.getStream());
        }

        // 兜底模型
        CustomerWorkRuntimeConfig.Fallback fb = dto.getFallback();
        ModelProperties.Fallback target = staged.getFallback();
        if (fb != null && fb.isEnabled()) {
            target.setEnabled(true);
            target.setProvider(fb.getProvider());
            target.setName(fb.getName());
            target.setBaseUrl(fb.getBaseUrl() == null ? "" : fb.getBaseUrl());
            target.setApiKey(StringUtils.hasText(fallbackApiKey) ? fallbackApiKey : "");
        } else {
            target.setEnabled(false);
        }

        // 重试
        CustomerWorkRuntimeConfig.Retry rt = dto.getRetry();
        ModelProperties.Retry retry = staged.getRetry();
        if (rt != null && rt.isEnabled()) {
            retry.setEnabled(true);
            retry.setMaxAttempts(rt.getMaxAttempts());
            retry.setBackoffMs(rt.getBackoffMs());
        } else {
            retry.setEnabled(false);
        }
        return staged;
    }

    /** 复制模型链构建相关字段（scalar + fallback/retry/tieredRouting 内部字段），不复制成本统计等无关项。 */
    private void copyModelCfg(ModelProperties from, ModelProperties to) {
        to.setProvider(from.getProvider());
        to.setApiKey(from.getApiKey());
        to.setName(from.getName());
        to.setBaseUrl(from.getBaseUrl());
        to.setTemperature(from.getTemperature());
        to.setMaxTokens(from.getMaxTokens());
        to.setStream(from.isStream());
        to.setTopP(from.getTopP());
        to.setReasoningEffort(from.getReasoningEffort());
        to.setEnableSearch(from.getEnableSearch());
        to.setEnableThinking(from.getEnableThinking());
        to.getFallback().setEnabled(from.getFallback().isEnabled());
        to.getFallback().setProvider(from.getFallback().getProvider());
        to.getFallback().setName(from.getFallback().getName());
        to.getFallback().setApiKey(from.getFallback().getApiKey());
        to.getFallback().setBaseUrl(from.getFallback().getBaseUrl());
        to.getRetry().setEnabled(from.getRetry().isEnabled());
        to.getRetry().setMaxAttempts(from.getRetry().getMaxAttempts());
        to.getRetry().setBackoffMs(from.getRetry().getBackoffMs());
        to.getTieredRouting().setEnabled(from.getTieredRouting().isEnabled());
        to.getTieredRouting().setProvider(from.getTieredRouting().getProvider());
        to.getTieredRouting().setName(from.getTieredRouting().getName());
        to.getTieredRouting().setApiKey(from.getTieredRouting().getApiKey());
        to.getTieredRouting().setBaseUrl(from.getTieredRouting().getBaseUrl());
        to.getTieredRouting().setMaxMessagesForEconomy(
            from.getTieredRouting().getMaxMessagesForEconomy());
        to.getTieredRouting().setMaxUserTextLengthForEconomy(
            from.getTieredRouting().getMaxUserTextLengthForEconomy());
    }

    /**
     * 在提交前完成 MCP 的全部遍历和映射。
     *
     * <p>运行时快照属于外部输入，列表中出现 null 也必须在任何共享配置写入之前拒绝；否则先回写模型、
     * 再映射 MCP 时抛错会留下“属性已变、delegate 未切”的半应用状态。</p>
     */
    private PreparedMcp prepareMcp(CustomerWorkRuntimeConfig dto) {
        List<CustomerWorkRuntimeConfig.McpServer> servers = dto.getMcpServers();
        if (CollectionUtils.isEmpty(servers)) {
            return new PreparedMcp(false, List.of());
        }
        List<McpProperties.Server> mapped = new ArrayList<>();
        for (CustomerWorkRuntimeConfig.McpServer s : servers) {
            if (s == null) {
                throw new IllegalArgumentException("runtime config MCP server is null");
            }
            McpProperties.Server server = new McpProperties.Server();
            server.setName(s.getName());
            server.setUrl(s.getUrl());
            server.setTransport(StringUtils.hasText(s.getTransport()) ? s.getTransport() : "sse");
            server.setHeaders(s.getHeaders());
            server.setAllowedSubjectTypes(new ArrayList<>(
                com.richard.fyoung.customerwork.tool.mcp.McpSubjectPolicy.parse(s.getAllowedSubjectTypes())
                    .stream().sorted().map(Enum::name).toList()));
            mapped.add(server);
        }
        return new PreparedMcp(true, List.copyOf(mapped));
    }

    /** 回写已准备完成的 MCP 快照；这里不再解析外部输入。 */
    private void applyMcp(PreparedMcp prepared) {
        McpProperties mcp = properties.getMcp();
        mcp.setEnabled(prepared.enabled());
        mcp.setServers(new ArrayList<>(prepared.servers()));
    }

    /** 在提交前把 Agent wire 语义解析为确定值。 */
    private PreparedAgent prepareAgent(CustomerWorkRuntimeConfig dto) {
        if (dto.getAgent() == null) {
            return new PreparedAgent(false, null);
        }
        Integer maxIters = dto.getAgent().getMaxIters();
        return new PreparedAgent(true, maxIters == null ? baselineMaxIters : maxIters);
    }

    /** Agent section 缺失表示不动；section 存在且 maxIters 为空表示重置为部署基线。 */
    private void applyAgent(PreparedAgent prepared) {
        if (prepared.present()) {
            properties.getAgent().setMaxIters(prepared.maxIters());
        }
    }

    private record PreparedMcp(boolean enabled, List<McpProperties.Server> servers) {
    }

    private record PreparedAgent(boolean present, Integer maxIters) {
    }
}
