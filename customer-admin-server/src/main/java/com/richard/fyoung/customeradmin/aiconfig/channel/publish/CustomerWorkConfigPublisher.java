package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.tool.mcp.McpClientFactory;
import com.richard.fyoung.customerwork.tool.mcp.McpServerSpec;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentBackupModel;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.service.McpCredentialService;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelHealthRuntimeAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelRoutingPolicyRuntimeAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.experiment.service.ModelExperimentRuntimeAccess;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.billing.service.ModelPriceService;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.service.RuntimeRollbackPatch;
import com.richard.fyoung.customeradmin.configversion.service.RuntimeRollbackPatchExtractor;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.entity.PublishScope;
import com.richard.fyoung.customeradmin.configversion.service.ConfigVersionService;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigContentHasher;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigSignature;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 客服机器人运行时配置发布器（仿 {@code NacosSkillPublisher}）。
 *
 * <p>读渠道绑定 → 组装 {@link CustomerWorkRuntimeConfig}（API Key 密文原样携带、兜底取备用模型第一个）→
 * 发布前<b>强制连通性探测</b>（不通过则拒绝发布，防坏配置下发）→ publishConfig 到 Nacos，8080 监听热生效。</p>
 *
 * <p>默认关闭（{@code admin.runtime-publish.nacos.enabled=false}）：关闭时所有发布方法直接跳过，不影响
 * 任何现有后台链路。开启后，智能体/模型保存会在同一事务登记持久化发布任务；独立 worker 以租约抢占、
 * 指数退避重试，避免 Nacos 短时不可用导致配置永久漏发。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class CustomerWorkConfigPublisher {


    private static final Logger log = LoggerFactory.getLogger(CustomerWorkConfigPublisher.class);

    private static final String CODE_PUBLISH_FAIL = "RUNTIME-PUBLISH-FAIL";
    private static final String CODE_PROBE_BLOCK = "RUNTIME-PUBLISH-PROBE-BLOCK";
    private final AiChannelBindingMapper channelBindingMapper;
    private final AiAgentMapper agentMapper;
    private final ModelConfigAccess modelConfigAccess;
    private final AiAgentBackupModelMapper agentBackupModelMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiMcpMapper mcpMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AdminModelFactory modelFactory;
    private final SecretRefService secretRefService;
    private final McpCredentialService mcpCredentialService;
    private final ModelRoutingPolicyRuntimeAccess routingPolicyRuntimeAccess;
    private final ModelExperimentRuntimeAccess experimentRuntimeAccess;
    private final ModelHealthRuntimeAccess healthRuntimeAccess;
    private final RuntimePublishProperties properties;
    /** 发布快照记录；为 null 时只发布不留版本（版本化未装配的场景，行为与引入版本化之前一致）。 */
    private final ConfigVersionService versionService;
    private final RuntimePublishTaskService taskService;
    private final ModelPriceService modelPriceService;
    private final boolean tenantEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeRollbackPatchExtractor rollbackPatchExtractor =
        new RuntimeRollbackPatchExtractor(objectMapper);
    private final McpClientFactory mcpClientFactory = new McpClientFactory();

    private volatile ConfigService configService;

    /** 不留版本快照的构造（测试与未装配版本化时用），行为与引入版本化之前一致。 */
    public CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                       ModelConfigAccess modelConfigAccess,
                                       AiAgentBackupModelMapper agentBackupModelMapper,
                                       AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                       AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                       RuntimePublishProperties properties) {
        this(channelBindingMapper, agentMapper, modelConfigAccess, agentBackupModelMapper,
            agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, properties,
            null, null, null, (ConfigVersionService) null, (RuntimePublishTaskService) null,
            null, null, null, false);
    }

    CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                ModelConfigAccess modelConfigAccess,
                                AiAgentBackupModelMapper agentBackupModelMapper,
                                AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                RuntimePublishProperties properties, boolean tenantEnabled) {
        this(channelBindingMapper, agentMapper, modelConfigAccess, agentBackupModelMapper,
            agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, properties,
            null, null, null, (ConfigVersionService) null, (RuntimePublishTaskService) null,
            null, null, null, tenantEnabled);
    }

    /**
     * Spring 注入构造。
     *
     * <p><b>必须标 {@code @Autowired}</b>：本类有多个构造器，不指明会让 Spring 去找无参构造并启动失败
     * （本项目已反复踩过这个坑）。</p>
     */
    public CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                       ModelConfigAccess modelConfigAccess,
                                       AiAgentBackupModelMapper agentBackupModelMapper,
                                       AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                       AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                       SecretRefService secretRefService,
                                       ObjectProvider<ModelRoutingPolicyRuntimeAccess> routingPolicyRuntimeAccessProvider,
                                       RuntimePublishProperties properties,
                                       AdminTenantProperties tenantProperties,
                                       ObjectProvider<ConfigVersionService> versionServiceProvider,
                                       ObjectProvider<RuntimePublishTaskService> taskServiceProvider) {
        this(channelBindingMapper, agentMapper, modelConfigAccess, agentBackupModelMapper,
            agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, secretRefService,
            routingPolicyRuntimeAccessProvider, null, properties, tenantProperties,
            versionServiceProvider, taskServiceProvider, null, null, null);
    }

    /** 兼容显式装配在线实验能力的既有测试与扩展调用方。 */
    public CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                       ModelConfigAccess modelConfigAccess,
                                       AiAgentBackupModelMapper agentBackupModelMapper,
                                       AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                       AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                       SecretRefService secretRefService,
                                       ObjectProvider<ModelRoutingPolicyRuntimeAccess> routingPolicyRuntimeAccessProvider,
                                       ObjectProvider<ModelExperimentRuntimeAccess> experimentRuntimeAccessProvider,
                                       RuntimePublishProperties properties,
                                       AdminTenantProperties tenantProperties,
                                       ObjectProvider<ConfigVersionService> versionServiceProvider,
                                       ObjectProvider<RuntimePublishTaskService> taskServiceProvider) {
        this(channelBindingMapper, agentMapper, modelConfigAccess, agentBackupModelMapper,
            agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, secretRefService,
            routingPolicyRuntimeAccessProvider, experimentRuntimeAccessProvider, properties,
            tenantProperties, versionServiceProvider, taskServiceProvider, null, null, null);
    }

    @Autowired
    public CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                       ModelConfigAccess modelConfigAccess,
                                       AiAgentBackupModelMapper agentBackupModelMapper,
                                       AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                       AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                       SecretRefService secretRefService,
                                       ObjectProvider<ModelRoutingPolicyRuntimeAccess> routingPolicyRuntimeAccessProvider,
                                       ObjectProvider<ModelExperimentRuntimeAccess> experimentRuntimeAccessProvider,
                                       RuntimePublishProperties properties,
                                       AdminTenantProperties tenantProperties,
                                       ObjectProvider<ConfigVersionService> versionServiceProvider,
                                       ObjectProvider<RuntimePublishTaskService> taskServiceProvider,
                                       ObjectProvider<ModelPriceService> modelPriceServiceProvider,
                                       ObjectProvider<McpCredentialService> mcpCredentialServiceProvider,
                                       ObjectProvider<ModelHealthRuntimeAccess> healthRuntimeAccessProvider) {
        this(channelBindingMapper, agentMapper, modelConfigAccess, agentBackupModelMapper,
            agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, properties,
            secretRefService,
            routingPolicyRuntimeAccessProvider == null
                ? null : routingPolicyRuntimeAccessProvider.getIfAvailable(),
            experimentRuntimeAccessProvider == null
                ? null : experimentRuntimeAccessProvider.getIfAvailable(),
            versionServiceProvider == null ? null : versionServiceProvider.getIfAvailable(),
            taskServiceProvider == null ? null : taskServiceProvider.getIfAvailable(),
            modelPriceServiceProvider == null ? null : modelPriceServiceProvider.getIfAvailable(),
            mcpCredentialServiceProvider == null ? null : mcpCredentialServiceProvider.getIfAvailable(),
            healthRuntimeAccessProvider == null ? null : healthRuntimeAccessProvider.getIfAvailable(),
            tenantProperties.isEnabled());
    }

    private CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                        ModelConfigAccess modelConfigAccess,
                                        AiAgentBackupModelMapper agentBackupModelMapper,
                                        AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                        AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                        RuntimePublishProperties properties,
                                        SecretRefService secretRefService,
                                        ModelRoutingPolicyRuntimeAccess routingPolicyRuntimeAccess,
                                        ModelExperimentRuntimeAccess experimentRuntimeAccess,
                                        ConfigVersionService versionService,
                                        RuntimePublishTaskService taskService,
                                        ModelPriceService modelPriceService,
                                        McpCredentialService mcpCredentialService,
                                        ModelHealthRuntimeAccess healthRuntimeAccess,
                                        boolean tenantEnabled) {
        this.channelBindingMapper = channelBindingMapper;
        this.agentMapper = agentMapper;
        this.modelConfigAccess = modelConfigAccess;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.mcpMapper = mcpMapper;
        this.cryptoUtil = cryptoUtil;
        this.modelFactory = modelFactory;
        this.secretRefService = secretRefService;
        this.mcpCredentialService = mcpCredentialService;
        this.routingPolicyRuntimeAccess = routingPolicyRuntimeAccess;
        this.experimentRuntimeAccess = experimentRuntimeAccess;
        this.healthRuntimeAccess = healthRuntimeAccess;
        this.properties = properties;
        this.versionService = versionService;
        this.taskService = taskService;
        this.modelPriceService = modelPriceService;
        this.tenantEnabled = tenantEnabled;
    }

    /** 发布能力是否启用（供 Controller 手动发布前门禁判断）。 */
    public boolean isEnabled() {
        return properties.getNacos().isEnabled();
    }

    /**
     * 冻结当前 Agent 可发布候选的非密钥版本绑定，供 badcase/知识盲区在复评前绑定精确制品。
     *
     * <p>这里只组装候选，不做 Nacos 外写或连通性探测；真正发布仍必须进入可靠任务、Eval gate 与 ACK 状态机。</p>
     */
    public EvalVersionBinding previewVersionBinding(Long agentId) {
        if (!isEnabled()) {
            throw new IllegalStateException("runtime config publishing is disabled");
        }
        if (agentId == null || !hasEnabledAgent(agentId) || !hasEnabledBinding(agentId)) {
            throw new IllegalArgumentException("enabled agent/channel binding not found: " + agentId);
        }
        AiAgent agent = agentMapper.selectById(agentId);
        AiModelConfig primary = modelConfigAccess.findVisibleById(agent.getModelId());
        if (primary == null) {
            throw new IllegalStateException("primary model not found for agent: " + agent.getAgentCode());
        }
        CustomerWorkRuntimeConfig payload = assemble(agent, primary, null, true);
        return EvalVersionBinding.fromRuntimeConfig(payload);
    }

    /**
     * 自动触发发布：某智能体被改动后，若它命中启用中的渠道绑定则重新下发运行时配置。
     * 可靠任务与业务修改同事务提交，发布失败由 worker 自动退避重试。
     *
     * <p>正常容器中先把任务与业务修改同事务落库；仅在未装配任务服务的兼容场景，才由
     * {@link #runAfterCommitOrNow} 在提交后直接发布。</p>
     */
    public String publishForAgentId(Long agentId) {
        if (!isEnabled() || agentId == null) {
            return null;
        }
        if (taskService != null) {
            if (!hasEnabledBinding(agentId) || !hasEnabledAgent(agentId)) {
                return null;
            }
            return taskService.enqueueAgent(agentId);
        }
        // afterCommit 发生时调用线程可能已恢复到原租户，必须在注册回调前捕获引用方租户。
        String tenantId = tenantEnabled ? TenantContext.require() : null;
        runAfterCommitOrNow(() -> runInTenant(tenantId, () -> doPublishForAgentId(agentId)));
        return null;
    }

    /**
     * 健康状态变化必须可靠下发，即使主模型当前不可连通也不能被发布前探测拦住。
     * 该入口只登记 HEALTH_OVERLAY 任务，实际发布仍走 fencing、签名和冻结目标 ACK。
     */
    public String publishHealthOverlayForAgentId(Long agentId) {
        if (!isEnabled() || agentId == null || !hasEnabledBinding(agentId) || !hasEnabledAgent(agentId)) {
            return null;
        }
        if (taskService == null) {
            throw new IllegalStateException("reliable runtime publish task service is required for health overlay");
        }
        return taskService.enqueueHealthOverlay(agentId);
    }

    private boolean hasEnabledAgent(Long agentId) {
        return agentMapper.selectCount(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getId, agentId)
            .eq(AiAgent::getStatus, StatusFlags.ENABLED)) > 0;
    }

    /**
     * 发布显式撤销快照。调用方必须在删除 Agent 行前传入已读取的 targetCode；可靠任务与业务变更同事务提交。
     */
    public String revokeForAgentId(Long agentId, String targetCode) {
        if (!isEnabled() || agentId == null || !StringUtils.hasText(targetCode)) {
            return null;
        }
        if (taskService == null) {
            throw new IllegalStateException("reliable runtime publish task service is required for revocation");
        }
        return taskService.enqueueRevocation(agentId, targetCode);
    }

    /**
     * 发布在线实验的不可变激活/撤流意图。
     *
     * @return 可靠任务 ID；Nacos 关闭、无启用渠道绑定或兼容构造未装配任务服务时返回 null
     */
    public String publishExperiment(Long agentId, Long experimentId,
                                    ModelExperimentPublishAction action) {
        if (!isEnabled() || agentId == null || experimentId == null || action == null
            || taskService == null || !hasEnabledBinding(agentId)) {
            return null;
        }
        return taskService.enqueueExperiment(agentId, experimentId, action);
    }

    /**
     * 事务感知调度：有活跃事务同步时注册 afterCommit 回调（提交才发布，回滚天然不发布）；
     * 无事务上下文时降级为立即执行（不静默丢失，覆盖非事务调用点如渠道绑定管理）。
     */
    private void runAfterCommitOrNow(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    /** 实际按智能体发布（已在事务外/提交后执行）：逐绑定发布，单绑定失败不影响其余。 */
    private void doPublishForAgentId(Long agentId) {
        List<AiChannelBinding> bindings = channelBindingMapper.selectList(
            new LambdaQueryWrapper<AiChannelBinding>()
                .eq(AiChannelBinding::getAgentId, agentId)
                .eq(AiChannelBinding::getStatus, StatusFlags.ENABLED));
        for (AiChannelBinding binding : bindings) {
            try {
                doPublish(binding);
            } catch (Exception e) {
                log.error("auto publish runtime config failed, code={}, channel={}, agentId={}",
                    CODE_PUBLISH_FAIL, binding.getChannelCode(), agentId, e);
            }
        }
    }

    private void runInTenant(String tenantId, Runnable action) {
        if (!tenantEnabled) {
            action.run();
            return;
        }
        TenantContext.runWith(tenantId, action);
    }

    private boolean hasEnabledBinding(Long agentId) {
        return channelBindingMapper.selectCount(new LambdaQueryWrapper<AiChannelBinding>()
            .eq(AiChannelBinding::getAgentId, agentId)
            .eq(AiChannelBinding::getStatus, StatusFlags.ENABLED)) > 0;
    }

    /**
     * 手动重发（渠道绑定页「重新发布」按钮）：正常容器先可靠入队，探测与投递结果由任务状态呈现。
     *
     * @return 正常容器返回可靠任务 ID；兼容测试/未装配任务服务时返回发布使用的 dataId
     */
    public String republishByChannel(String channelCode) {
        AiChannelBinding binding = channelBindingMapper.selectOne(
            new LambdaQueryWrapper<AiChannelBinding>().eq(AiChannelBinding::getChannelCode, channelCode));
        if (binding == null) {
            throw new IllegalArgumentException("channel binding not found: " + channelCode);
        }
        if (binding.getStatus() == null || binding.getStatus() != StatusFlags.ENABLED
            || !hasEnabledAgent(binding.getAgentId())) {
            throw new IllegalStateException("channel binding or agent is disabled: " + channelCode);
        }
        if (taskService != null) {
            return taskService.enqueueAgent(binding.getAgentId());
        }
        return doPublish(binding);
    }

    /**
     * 组装 + 探测 + 发布。探测不过或 publishConfig 返回 false 抛 {@link IllegalStateException}。
     * @return 发布使用的 dataId
     */
    private String doPublish(AiChannelBinding binding) {
        AiAgent agent = agentMapper.selectById(binding.getAgentId());
        if (agent == null) {
            throw new IllegalStateException("bound agent not found: " + binding.getAgentId());
        }
        AiModelConfig primary = modelConfigAccess.findVisibleById(agent.getModelId());
        if (primary == null) {
            throw new IllegalStateException("primary model not found for agent: " + agent.getAgentCode());
        }
        // 发布门禁：强制连通性探测（解密密钥仅用于探测，不进入下发载荷）
        assertConnectivity(primary);

        // 同一 dataId 是 Agent 级全局快照，绑定页触发来源不能冒充每次请求的运行时渠道事实。
        CustomerWorkRuntimeConfig payload = assemble(agent, primary, null);
        enrichMetadata(payload, UUID.randomUUID().toString());
        String json = serialize(payload);
        return publishJson(agent.getAgentCode(), agent.getId(), json,
            binding.getChannelCode(), PublishScope.FULL, null, null, null);
    }

    /**
     * 把当前权威数据组装出的 JSON 下发到 Nacos，并留一份版本快照。
     * 历史回滚同样只在白名单补丁应用到当前组装结果后进入此私有出口，不接受外部原始 JSON。
     *
     * <p>失败也记一条 FAILED 版本：排查"线上为什么还是旧配置"时，一条失败留痕比什么都没有有用得多。</p>
     *
     * @return 发布使用的 dataId
     */
    private String publishJson(String targetCode, Long targetId, String json, String channelCode,
                               PublishScope scope, String grayTenants, Integer sourceVersion, String remark) {
        String dataId = resolveDataId(scope, grayTenants);
        return publishJsonToDataId(targetCode, targetId, json, channelCode, scope,
            grayTenants, sourceVersion, remark, dataId, properties.getNacos().getGroup());
    }

    private String publishJsonToDataId(String targetCode, Long targetId, String json, String channelCode,
                                       PublishScope scope, String grayTenants, Integer sourceVersion,
                                       String remark, String dataId, String groupName) {
        try {
            boolean ok = configService().publishConfig(dataId, groupName, json);
            if (!ok) {
                throw new IllegalStateException("nacos publishConfig returned false");
            }
        } catch (Exception e) {
            recordFailure(targetCode, targetId, json, e.getMessage());
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("publish runtime config to nacos failed: " + e.getMessage(), e);
        }
        if (versionService != null) {
            versionService.recordPublish(ConfigType.AGENT, targetCode, targetId, json, dataId,
                scope, grayTenants, sourceVersion, remark);
        }
        log.info("runtime config published, channel={}, agent={}, dataId={}, group={}, scope={}",
            channelCode, targetCode, dataId, groupName, scope);
        return dataId;
    }

    /**
     * 灰度发布用独立 dataId：{@code <dataId>-tenant-<租户码>}。
     *
     * <p>客服端按自己的租户读对应 dataId，读不到再回落主 dataId——因此灰度版本只影响
     * 名单内的租户，其余租户继续用主 dataId 上的全量版本，不需要客服端理解"灰度"这个概念。</p>
     *
     * <p>可靠灰度任务会在各自租户上下文中通过 {@link #resolveTaskDataId(String)} 解析专属 dataId；
     * 本方法仅供不装配可靠任务服务的兼容发布路径。</p>
     */
    private String resolveDataId(PublishScope scope, String grayTenants) {
        String base = properties.getNacos().getDataId();
        if (scope != PublishScope.GRAY || grayTenants == null || grayTenants.isBlank()) {
            return base;
        }
        return base;
    }

    /** 灰度下发到指定租户的 dataId。 */
    public String grayDataId(String tenantCode) {
        String candidate = tenantCode == null ? null : tenantCode.trim();
        if (!TenantContext.isValidTenantId(candidate)) {
            throw new IllegalArgumentException("tenantCode format is invalid");
        }
        return properties.getNacos().getDataId() + "-tenant-"
            + TenantContext.canonicalizeTenantId(candidate);
    }


    /**
     * 在目标租户上下文中以当前权威资产重组安全回滚候选并完成发布前探测。
     *
     * @return 该租户下与目标编码对应的当前 Agent 主键
     */
    public Long validateSafePublishCandidate(String targetCode, String rollbackPatchJson,
                                             RuntimePublishIntent publishIntent) {
        if (!isEnabled()) {
            throw new BizException(ResultCode.RUNTIME_PUBLISH_DISABLED);
        }
        if (!StringUtils.hasText(targetCode) || publishIntent == null
            || !publishIntent.requiresRollbackPatch()) {
            throw new BizException(ResultCode.PARAM_INVALID, "安全发布目标或意图无效");
        }
        rollbackPatchExtractor.deserialize(rollbackPatchJson);
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getAgentCode, targetCode));
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "目标租户不存在同编码智能体");
        }

        RuntimePublishTask validationTask = new RuntimePublishTask();
        validationTask.setTenantId(tenantEnabled ? TenantContext.require() : TenantContext.DEFAULT);
        validationTask.setTargetId(agent.getId());
        validationTask.setPublishIntent(publishIntent.name());
        validationTask.setRollbackPatchJson(rollbackPatchJson);
        validationTask.setPublishScope(publishIntent == RuntimePublishIntent.SAFE_GRAY ? "GRAY" : "FULL");
        validationTask.setCreatedAtMs(System.currentTimeMillis());
        prepareTask(validationTask);
        return agent.getId();
    }

    /** 为可靠任务组装当前快照。快照只存于 worker 内存，任务表不复制密钥密文。 */
    public PreparedRuntimeConfig prepareTask(RuntimePublishTask task) {
        if (publishIntent(task).isRevocation()) {
            return prepareRevocation(task);
        }
        List<AiChannelBinding> bindings = channelBindingMapper.selectList(
            new LambdaQueryWrapper<AiChannelBinding>()
                .eq(AiChannelBinding::getAgentId, task.getTargetId())
                .eq(AiChannelBinding::getStatus, StatusFlags.ENABLED));
        if (CollectionUtils.isEmpty(bindings)) {
            throw new IllegalStateException("enabled channel binding not found for agent: " + task.getTargetId());
        }
        AiAgent agent = agentMapper.selectById(task.getTargetId());
        if (agent == null) {
            throw new IllegalStateException("bound agent not found: " + task.getTargetId());
        }
        AiModelConfig primary = modelConfigAccess.findVisibleById(agent.getModelId());
        if (primary == null) {
            throw new IllegalStateException("primary model not found for agent: " + agent.getAgentCode());
        }
        RuntimePublishIntent intent = publishIntent(task);
        ModelExperimentPublishAction experimentAction = experimentAction(task);
        if (!intent.bypassesConnectivityGate()
            && experimentAction != ModelExperimentPublishAction.DEACTIVATE) {
            assertConnectivity(primary);
        }
        String revision = StringUtils.hasText(task.getRevision())
            ? task.getRevision() : UUID.randomUUID().toString();
        // 一个 Agent 可以绑定多个渠道；可靠任务只发布一份全局快照，不能把无序首绑定固化为默认路由事实。
        // 渠道条件应由消费端在每次请求时注入 ModelRouteHint；未注入时只能命中无条件默认规则。
        CustomerWorkRuntimeConfig payload = assemble(agent, primary, null, experimentAction == null);
        applyExperimentIntent(payload, task, experimentAction);
        enrichPricing(payload);
        applySafeRollbackPatch(payload, task);
        String publishedAt = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(task.getCreatedAtMs()), ZoneOffset.UTC).toString();
        enrichMetadata(payload, revision, publishedAt);
        String taskDataId = StringUtils.hasText(task.getDataId())
            ? task.getDataId() : resolveTaskDataId(task.getTenantId());
        String taskGroupName = StringUtils.hasText(task.getGroupName())
            ? task.getGroupName() : properties.getNacos().getGroup();
        return new PreparedRuntimeConfig(agent.getAgentCode(), null,
            taskDataId, taskGroupName, revision,
            payload.getContentHash(), serialize(payload), EvalVersionBinding.fromRuntimeConfig(payload));
    }

    /** 撤销任务只依赖入队时固化的目标，不读取已经停用/删除的 Agent、绑定、模型或 MCP 行。 */
    private PreparedRuntimeConfig prepareRevocation(RuntimePublishTask task) {
        if (!StringUtils.hasText(task.getTargetCode())) {
            throw new IllegalStateException("runtime revocation targetCode is missing");
        }
        CustomerWorkRuntimeConfig payload = new CustomerWorkRuntimeConfig();
        payload.setSchemaVersion(5);
        payload.setActive(Boolean.FALSE);
        payload.setTargetCode(task.getTargetCode());
        String revision = StringUtils.hasText(task.getRevision())
            ? task.getRevision() : UUID.randomUUID().toString();
        String publishedAt = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(task.getCreatedAtMs()), ZoneOffset.UTC).toString();
        enrichMetadata(payload, revision, publishedAt);
        String taskDataId = StringUtils.hasText(task.getDataId())
            ? task.getDataId() : resolveTaskDataId(task.getTenantId());
        String taskGroupName = StringUtils.hasText(task.getGroupName())
            ? task.getGroupName() : properties.getNacos().getGroup();
        return new PreparedRuntimeConfig(task.getTargetCode(), null, taskDataId, taskGroupName,
            revision, payload.getContentHash(), serialize(payload), null);
    }

    private ModelExperimentPublishAction experimentAction(RuntimePublishTask task) {
        if (!StringUtils.hasText(task.getExperimentPublishAction())) {
            if (task.getExperimentId() != null) {
                throw new IllegalStateException("experiment publish action is missing");
            }
            return null;
        }
        if (task.getExperimentId() == null) {
            throw new IllegalStateException("experiment publish task id is missing");
        }
        try {
            return ModelExperimentPublishAction.valueOf(task.getExperimentPublishAction());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "unsupported experiment publish action: " + task.getExperimentPublishAction(), e);
        }
    }

    private void applyExperimentIntent(CustomerWorkRuntimeConfig payload,
                                       RuntimePublishTask task,
                                       ModelExperimentPublishAction action) {
        if (action == null) {
            return;
        }
        if (action == ModelExperimentPublishAction.DEACTIVATE) {
            payload.setOnlineExperiment(null);
            return;
        }
        if (experimentRuntimeAccess == null) {
            throw new IllegalStateException("model experiment runtime access is unavailable");
        }
        payload.setOnlineExperiment(experimentRuntimeAccess.requireRunning(
            task.getTargetId(), task.getExperimentId()));
    }

    private void applySafeRollbackPatch(CustomerWorkRuntimeConfig payload, RuntimePublishTask task) {
        RuntimePublishIntent intent = publishIntent(task);
        if (!intent.requiresRollbackPatch()) {
            if (StringUtils.hasText(task.getRollbackPatchJson())) {
                throw new IllegalStateException("normal runtime publish task must not contain rollback patch");
            }
            return;
        }
        if (!StringUtils.hasText(task.getRollbackPatchJson())) {
            throw new IllegalStateException("safe runtime publish task rollback patch is missing");
        }
        RuntimeRollbackPatch patch = rollbackPatchExtractor.deserialize(task.getRollbackPatchJson());
        payload.setSystemPrompt(patch.systemPrompt());
        payload.getAgent().setMaxIters(patch.maxIters());
    }

    private RuntimePublishIntent publishIntent(RuntimePublishTask task) {
        if (!StringUtils.hasText(task.getPublishIntent())) {
            return RuntimePublishIntent.NORMAL;
        }
        try {
            return RuntimePublishIntent.valueOf(task.getPublishIntent());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unsupported runtime publish intent: " + task.getPublishIntent(), e);
        }
    }

    /** 任务 worker 复用既有 Nacos 发布入口，失败交给任务退避重试。 */
    public void publishPrepared(RuntimePublishTask task, PreparedRuntimeConfig prepared) {
        publishJsonToDataId(prepared.targetCode(), task.getTargetId(), prepared.json(), prepared.channelCode(),
            PublishScope.valueOf(task.getPublishScope()), task.getGrayTenants(),
            task.getSourceVersion(), task.getRemark(), prepared.dataId(), prepared.groupName());
    }

    /** 多租户任务必须写租户专属 dataId，禁止不同租户互相覆盖模型密文。 */
    private String resolveTaskDataId(String tenantId) {
        if (!tenantEnabled) {
            return properties.getNacos().getDataId();
        }
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalStateException("runtime publish task tenant is missing");
        }
        return grayDataId(tenantId.trim());
    }

    public record PreparedRuntimeConfig(String targetCode, String channelCode, String dataId,
                                        String groupName, String revision, String contentHash,
                                        String json, EvalVersionBinding versionBinding) {

        /** 兼容既有单测与扩展调用方；未提供版本时门禁会 fail-closed。 */
        public PreparedRuntimeConfig(String targetCode, String channelCode, String dataId,
                                     String groupName, String revision, String contentHash,
                                     String json) {
            this(targetCode, channelCode, dataId, groupName, revision, contentHash, json, null);
        }
    }

    private void recordFailure(String targetCode, Long targetId, String json, String reason) {
        if (versionService == null) {
            return;
        }
        try {
            versionService.recordFailure(ConfigType.AGENT, targetCode, targetId, json, reason);
        } catch (Exception e) {
            // 留痕失败不该掩盖真正的发布失败：那个异常正要往上抛
            log.error("record publish failure version failed, code={}, target={}",
                "CONFIG-VERSION-RECORD-FAIL", targetCode, e);
        }
    }

    /** 连通性门禁：解密主模型密钥后走既有探测协议，非成功即拒绝发布。 */
    private void assertConnectivity(AiModelConfig primary) {
        String apiKey = resolvePlaintext(primary);
        ModelTestResult result = modelFactory.testConnectivity(
            primary.getProvider(), primary.getBaseUrl(), apiKey, primary.getModel());
        if (result.testStatus() != ConnectivityTestStatus.SUCCESS) {
            log.error("runtime config publish blocked by connectivity probe, code={}, model={}, msg={}",
                CODE_PROBE_BLOCK, primary.getModelName(), result.message());
            throw new IllegalStateException("primary model connectivity probe failed: " + result.message());
        }
    }

    /** 组装运行时配置载荷：模型密文原样携带；兜底取备用模型第一个（sort_order 升序）。包内可见供单测。 */
    CustomerWorkRuntimeConfig assemble(AiAgent agent, AiModelConfig primary) {
        return assemble(agent, primary, null);
    }

    CustomerWorkRuntimeConfig assemble(AiAgent agent, AiModelConfig primary, String channelCode) {
        return assemble(agent, primary, channelCode, true);
    }

    private CustomerWorkRuntimeConfig assemble(AiAgent agent, AiModelConfig primary,
                                               String channelCode,
                                               boolean includeCurrentExperiment) {
        CustomerWorkRuntimeConfig cfg = new CustomerWorkRuntimeConfig();
        cfg.setSchemaVersion(5);
        cfg.setActive(Boolean.TRUE);
        cfg.setTargetCode(agent.getAgentCode());
        cfg.setPublishedAt(LocalDateTime.now().toString());
        cfg.setSystemPrompt(agent.getSystemPrompt());

        CustomerWorkRuntimeConfig.Model model = cfg.getModel();
        model.setDeploymentId(primary.getId());
        model.setProvider(primary.getProvider());
        model.setName(primary.getModel());
        model.setBaseUrl(primary.getBaseUrl());
        model.setApiKeyCipher(resolveCipherText(primary));
        model.setHealth(healthOverlay(primary));

        if (agent.getModelRoutePolicyId() == null) {
            cfg.setFallback(assembleFallback(agent.getId(), primary.getId()));
        } else {
            if (routingPolicyRuntimeAccess == null) {
                throw new IllegalStateException("model routing runtime access is unavailable");
            }
            CustomerWorkRuntimeConfig.RoutingPolicy routing = routingPolicyRuntimeAccess.requireActive(
                agent.getModelRoutePolicyId(), agent.getId(), channelCode);
            cfg.setRoutingPolicy(routing);
            cfg.setFallback(assemblePolicyFallback(routing));
        }
        cfg.setMcpServers(assembleMcpServers(agent.getId()));
        if (includeCurrentExperiment && experimentRuntimeAccess != null) {
            cfg.setOnlineExperiment(experimentRuntimeAccess.runningForAgent(agent.getId()));
        }
        enrichPricing(cfg);

        CustomerWorkRuntimeConfig.Agent agentCfg = new CustomerWorkRuntimeConfig.Agent();
        agentCfg.setMaxIters(agent.getMaxIters());
        cfg.setAgent(agentCfg);
        return cfg;
    }

    /** 内容摘要排除发布时间和 revision，用于崩溃重试时核对快照未漂移。 */
    private void enrichMetadata(CustomerWorkRuntimeConfig payload, String revision) {
        enrichMetadata(payload, revision, LocalDateTime.now().toString());
    }

    private void enrichMetadata(CustomerWorkRuntimeConfig payload, String revision, String publishedAt) {
        payload.setContentHash(RuntimeConfigContentHasher.compute(payload, objectMapper));
        payload.setPublishedAt(publishedAt);
        payload.setRevision(revision);
        if (properties.getSigning().isEnabled()) {
            payload.setSignatureKeyId(properties.getSigning().getKeyId());
            payload.setSignatureAlgorithm(RuntimeConfigSignature.ALGORITHM);
            payload.setSignature(RuntimeConfigSignature.sign(payload, properties.getSigning().getSecret()));
        }
    }

    /** 兜底模型 = 智能体备用模型列表第一个（按 sort_order 升序）；无备用则不启用兜底。 */
    private CustomerWorkRuntimeConfig.Fallback assembleFallback(Long agentId, Long primaryModelId) {
        List<AiAgentBackupModel> backups = agentBackupModelMapper.selectList(
            new LambdaQueryWrapper<AiAgentBackupModel>()
                .eq(AiAgentBackupModel::getAgentId, agentId)
                .orderByAsc(AiAgentBackupModel::getSortOrder));
        for (AiAgentBackupModel backup : backups) {
            if (backup.getModelId().equals(primaryModelId)) {
                continue;
            }
            AiModelConfig fb = modelConfigAccess.findVisibleById(backup.getModelId());
            if (fb == null) {
                continue;
            }
            CustomerWorkRuntimeConfig.Fallback fallback = new CustomerWorkRuntimeConfig.Fallback();
            fallback.setEnabled(true);
            fallback.setDeploymentId(fb.getId());
            fallback.setProvider(fb.getProvider());
            fallback.setName(fb.getModel());
            fallback.setBaseUrl(fb.getBaseUrl());
            fallback.setApiKeyCipher(resolveCipherText(fb));
            fallback.setHealth(healthOverlay(fb));
            return fallback;
        }
        return null;
    }

    private CustomerWorkRuntimeConfig.Fallback assemblePolicyFallback(
        CustomerWorkRuntimeConfig.RoutingPolicy policy) {
        if (policy.getRules() == null || policy.getDeployments() == null) {
            return null;
        }
        Long fallbackId = policy.getRules().stream()
            .filter(rule -> "FALLBACK".equals(rule.getPurpose()))
            .map(CustomerWorkRuntimeConfig.RoutingRule::getDeploymentId)
            .findFirst().orElse(null);
        if (fallbackId == null) {
            return null;
        }
        return policy.getDeployments().stream()
            .filter(deployment -> fallbackId.equals(deployment.getDeploymentId()))
            .findFirst()
            .map(deployment -> {
                CustomerWorkRuntimeConfig.Fallback fallback = new CustomerWorkRuntimeConfig.Fallback();
                fallback.setEnabled(true);
                fallback.setDeploymentId(deployment.getDeploymentId());
                fallback.setProvider(deployment.getProvider());
                fallback.setName(deployment.getName());
                fallback.setBaseUrl(deployment.getBaseUrl());
                fallback.setApiKeyCipher(deployment.getApiKeyCipher());
                fallback.setPricing(deployment.getPricing());
                fallback.setHealth(deployment.getHealth());
                return fallback;
            })
            .orElseThrow(() -> new IllegalStateException("routing fallback deployment snapshot is missing"));
    }

    /** 对载荷内全部真实部署冻结同供应商、同模型价目；兼容构造未装配价格服务时显式 UNPRICED。 */
    private void enrichPricing(CustomerWorkRuntimeConfig config) {
        CustomerWorkRuntimeConfig.Model primary = config.getModel();
        primary.setPricing(priceSnapshot(primary.getProvider(), primary.getName()));
        CustomerWorkRuntimeConfig.Fallback fallback = config.getFallback();
        if (fallback != null) {
            fallback.setPricing(priceSnapshot(fallback.getProvider(), fallback.getName()));
        }
        if (config.getRoutingPolicy() != null && config.getRoutingPolicy().getDeployments() != null) {
            for (CustomerWorkRuntimeConfig.RoutingDeployment deployment
                : config.getRoutingPolicy().getDeployments()) {
                deployment.setPricing(priceSnapshot(deployment.getProvider(), deployment.getName()));
            }
        }
        CustomerWorkRuntimeConfig.OnlineExperiment experiment = config.getOnlineExperiment();
        if (experiment != null) {
            enrichArmPricing(experiment.getControl());
            enrichArmPricing(experiment.getTreatment());
        }
    }

    private void enrichArmPricing(CustomerWorkRuntimeConfig.ExperimentArm arm) {
        if (arm != null) {
            arm.setPricing(priceSnapshot(arm.getProvider(), arm.getName()));
        }
    }

    private CustomerWorkRuntimeConfig.Pricing priceSnapshot(String provider, String modelName) {
        return modelPriceService == null
            ? new CustomerWorkRuntimeConfig.Pricing()
            : modelPriceService.snapshot(provider, modelName);
    }

    private CustomerWorkRuntimeConfig.HealthOverlay healthOverlay(AiModelConfig model) {
        return healthRuntimeAccess == null ? null : healthRuntimeAccess.overlay(model);
    }

    private String resolveCipherText(AiModelConfig model) {
        return secretRefService == null
            ? model.getApiKey()
            : secretRefService.resolveCipherText(model.getSecretRefId(), model.getTenantId(), model.getApiKey());
    }

    private String resolvePlaintext(AiModelConfig model) {
        return secretRefService == null
            ? cryptoUtil.decrypt(model.getApiKey())
            : secretRefService.resolvePlaintext(model);
    }

    /** 组装 MCP：取智能体绑定且启用的 sse/http 型 MCP，解析 config 的 url/headers（stdio 型 8080 不支持，跳过）。 */
    private List<CustomerWorkRuntimeConfig.McpServer> assembleMcpServers(Long agentId) {
        List<Long> mcpIds = agentMcpMapper.selectList(
                new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, agentId))
            .stream().map(AiAgentMcp::getMcpId).toList();
        if (CollectionUtils.isEmpty(mcpIds)) {
            return new ArrayList<>();
        }
        List<CustomerWorkRuntimeConfig.McpServer> servers = new ArrayList<>();
        List<AiMcp> mcpConfigs = mcpMapper.selectBatchIds(mcpIds).stream()
            .sorted(Comparator.comparing(AiMcp::getId))
            .toList();
        for (AiMcp mcp : mcpConfigs) {
            if (mcp.getStatus() != null && mcp.getStatus() != StatusFlags.ENABLED) {
                continue;
            }
            String transport = resolveTransport(mcp.getMcpType());
            if (transport == null) {
                continue;   // stdio 等 8080 不支持的传输类型跳过
            }
            CustomerWorkRuntimeConfig.McpServer server = new CustomerWorkRuntimeConfig.McpServer();
            server.setName(mcp.getMcpName());
            server.setTransport(transport);
            server.setAllowedSubjectTypes(
                com.richard.fyoung.customerwork.tool.mcp.McpSubjectPolicy.toNames(mcp.getAllowedSubjectTypes()));
            String executableConfig = mcpCredentialService == null
                ? mcp.getConfig() : mcpCredentialService.resolve(mcp);
            fillUrlAndHeaders(server, mcp.getMcpType(), executableConfig);
            if (StringUtils.hasText(server.getUrl())) {
                servers.add(server);
            }
        }
        return servers;
    }

    private String resolveTransport(String mcpType) {
        if (McpServerSpec.TYPE_HTTP.equalsIgnoreCase(mcpType)) {
            return McpServerSpec.TRANSPORT_STREAMABLE_HTTP;
        }
        if (McpServerSpec.TYPE_SSE.equalsIgnoreCase(mcpType)) {
            return McpServerSpec.TYPE_SSE;
        }
        return null;
    }

    /** 解析 ai_mcp.config JSON 的 url 与 headers（复用 starter 的 MCP parser）。 */
    private void fillUrlAndHeaders(CustomerWorkRuntimeConfig.McpServer server, String mcpType, String config) {
        if (!StringUtils.hasText(config)) {
            return;
        }
        try {
            McpServerSpec spec = mcpClientFactory.parseSpec(server.getName(), mcpType, config);
            server.setUrl(spec.url());
            if (!CollectionUtils.isEmpty(spec.headers())) {
                server.setHeadersCipher(cryptoUtil.encrypt(objectMapper.writeValueAsString(spec.headers())));
            }
        } catch (Exception e) {
            // 不能静默跳过损坏的 MCP，否则发布成功但工具集已悄悄变化；任务层统一记录失败并重试/处置。
            throw new IllegalStateException("runtime publish MCP config is invalid: " + server.getName(), e);
        }
    }

    private String serialize(CustomerWorkRuntimeConfig payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("serialize runtime config failed: " + e.getMessage(), e);
        }
    }

    /** 懒初始化并复用单个 ConfigService（与 NacosSkillPublisher 一致）。 */
    private ConfigService configService() throws Exception {
        ConfigService local = configService;
        if (local == null) {
            synchronized (this) {
                local = configService;
                if (local == null) {
                    local = NacosFactory.createConfigService(buildProperties());
                    configService = local;
                }
            }
        }
        return local;
    }

    private Properties buildProperties() {
        RuntimePublishProperties.Nacos cfg = properties.getNacos();
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, cfg.getServerAddr());
        if (StringUtils.hasText(cfg.getNamespace())) {
            props.put(PropertyKeyConst.NAMESPACE, cfg.getNamespace());
        }
        if (StringUtils.hasText(cfg.getUsername())) {
            props.put(PropertyKeyConst.USERNAME, cfg.getUsername());
            props.put(PropertyKeyConst.PASSWORD, cfg.getPassword());
        }
        return props;
    }
}
