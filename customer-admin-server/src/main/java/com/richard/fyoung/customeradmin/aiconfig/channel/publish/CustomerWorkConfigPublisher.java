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
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.entity.PublishScope;
import com.richard.fyoung.customeradmin.configversion.service.ConfigVersionService;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
    private final RuntimePublishProperties properties;
    /** 发布快照记录；为 null 时只发布不留版本（版本化未装配的场景，行为与引入版本化之前一致）。 */
    private final ConfigVersionService versionService;
    private final RuntimePublishTaskService taskService;
    private final boolean tenantEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
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
            (ConfigVersionService) null, (RuntimePublishTaskService) null, false);
    }

    CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                ModelConfigAccess modelConfigAccess,
                                AiAgentBackupModelMapper agentBackupModelMapper,
                                AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                RuntimePublishProperties properties, boolean tenantEnabled) {
        this(channelBindingMapper, agentMapper, modelConfigAccess, agentBackupModelMapper,
            agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, properties,
            (ConfigVersionService) null, (RuntimePublishTaskService) null, tenantEnabled);
    }

    /**
     * Spring 注入构造。
     *
     * <p><b>必须标 {@code @Autowired}</b>：本类有多个构造器，不指明会让 Spring 去找无参构造并启动失败
     * （本项目已反复踩过这个坑）。</p>
     */
    @Autowired
    public CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                       ModelConfigAccess modelConfigAccess,
                                       AiAgentBackupModelMapper agentBackupModelMapper,
                                       AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                       AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                       RuntimePublishProperties properties,
                                       AdminTenantProperties tenantProperties,
                                       ObjectProvider<ConfigVersionService> versionServiceProvider,
                                       ObjectProvider<RuntimePublishTaskService> taskServiceProvider) {
        this(channelBindingMapper, agentMapper, modelConfigAccess, agentBackupModelMapper,
            agentMcpMapper, mcpMapper, cryptoUtil, modelFactory, properties,
            versionServiceProvider == null ? null : versionServiceProvider.getIfAvailable(),
            taskServiceProvider == null ? null : taskServiceProvider.getIfAvailable(),
            tenantProperties.isEnabled());
    }

    private CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                        ModelConfigAccess modelConfigAccess,
                                        AiAgentBackupModelMapper agentBackupModelMapper,
                                        AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                        AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                        RuntimePublishProperties properties, ConfigVersionService versionService,
                                        RuntimePublishTaskService taskService, boolean tenantEnabled) {
        this.channelBindingMapper = channelBindingMapper;
        this.agentMapper = agentMapper;
        this.modelConfigAccess = modelConfigAccess;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.mcpMapper = mcpMapper;
        this.cryptoUtil = cryptoUtil;
        this.modelFactory = modelFactory;
        this.properties = properties;
        this.versionService = versionService;
        this.taskService = taskService;
        this.tenantEnabled = tenantEnabled;
    }

    /** 发布能力是否启用（供 Controller 手动发布前门禁判断）。 */
    public boolean isEnabled() {
        return properties.getNacos().isEnabled();
    }

    /**
     * 自动触发发布：某智能体被改动后，若它命中启用中的渠道绑定则重新下发运行时配置。
     * 可靠任务与业务修改同事务提交，发布失败由 worker 自动退避重试。
     *
     * <p>正常容器中先把任务与业务修改同事务落库；仅在未装配任务服务的兼容场景，才由
     * {@link #runAfterCommitOrNow} 在提交后直接发布。</p>
     */
    public void publishForAgentId(Long agentId) {
        if (!isEnabled() || agentId == null) {
            return;
        }
        if (taskService != null) {
            if (!hasEnabledBinding(agentId)) {
                return;
            }
            taskService.enqueueAgent(agentId);
            return;
        }
        // afterCommit 发生时调用线程可能已恢复到原租户，必须在注册回调前捕获引用方租户。
        String tenantId = tenantEnabled ? TenantContext.require() : null;
        runAfterCommitOrNow(() -> runInTenant(tenantId, () -> doPublishForAgentId(agentId)));
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

        CustomerWorkRuntimeConfig payload = assemble(agent, primary);
        enrichMetadata(payload, UUID.randomUUID().toString());
        String json = serialize(payload);
        return publishJson(agent.getAgentCode(), agent.getId(), json,
            binding.getChannelCode(), PublishScope.FULL, null, null, null);
    }

    /**
     * 把已组装好的 JSON 下发到 Nacos，并留一份版本快照。
     *
     * <p>回滚复用这条路径：回滚就是"拿旧版本的内容再发一次"，与正常发布走同一段代码——
     * 若给回滚另写一条下发逻辑，两条路迟早在序列化或 dataId 拼装上产生差异。</p>
     *
     * <p>失败也记一条 FAILED 版本：排查"线上为什么还是旧配置"时，一条失败留痕比什么都没有有用得多。</p>
     *
     * @return 发布使用的 dataId
     */
    public String publishJson(String targetCode, Long targetId, String json, String channelCode,
                              PublishScope scope, String grayTenants, Integer sourceVersion, String remark) {
        String dataId = resolveDataId(scope, grayTenants);
        return publishJsonToDataId(targetCode, targetId, json, channelCode, scope,
            grayTenants, sourceVersion, remark, dataId);
    }

    /**
     * 把历史快照作为一个新的全量版本发布到当前有效租户。
     *
     * <p>多租户模式下发布目标只由 {@link TenantContext} 推导，绝不复用快照里的 dataId：
     * 后者是审计记录，不是可信的路由参数。这样即使历史版本来自旧的全局 dataId，回滚也只会
     * 写当前租户专属 dataId，不会覆盖全局基线或其他租户。</p>
     */
    public String publishRollbackToCurrentTenant(String targetCode, Long targetId, String json,
                                                  String sourceDataId, PublishScope sourceScope,
                                                  List<String> sourceGrayTenants, Integer sourceVersion,
                                                  String remark) {
        String dataId = resolveRollbackDataId(sourceDataId, sourceScope, sourceGrayTenants);
        return publishJsonToDataId(targetCode, targetId, json, null, PublishScope.FULL,
            null, sourceVersion, remark, dataId);
    }

    /**
     * 校验快照原发布目标与当前租户一致，并返回本次回滚的可信目标。
     * 包级可见仅供不触达 Nacos 的单元测试验证路由边界。
     */
    String resolveRollbackDataId(String sourceDataId, PublishScope sourceScope,
                                 List<String> sourceGrayTenants) {
        String baseDataId = properties.getNacos().getDataId();
        if (!tenantEnabled) {
            return baseDataId;
        }

        String currentTenant = TenantContext.require().trim();
        String currentTenantDataId = grayDataId(currentTenant);
        // V57 以前的版本可能只记录全局 base（或空值）。行级租户过滤已确认其归属，
        // 因此允许把这类旧快照安全迁移到当前租户专属 dataId。
        if (StringUtils.hasText(sourceDataId)
            && !baseDataId.equals(sourceDataId)
            && !currentTenantDataId.equals(sourceDataId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "回滚快照的发布目标不属于当前租户");
        }

        if (sourceScope == PublishScope.GRAY) {
            boolean containsCurrentTenant = sourceGrayTenants != null && sourceGrayTenants.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(currentTenant::equals);
            if (!containsCurrentTenant) {
                throw new BizException(ResultCode.PARAM_INVALID, "灰度快照不包含当前租户");
            }
        }
        return currentTenantDataId;
    }

    private String publishJsonToDataId(String targetCode, Long targetId, String json, String channelCode,
                                       PublishScope scope, String grayTenants, Integer sourceVersion,
                                       String remark, String dataId) {
        try {
            boolean ok = configService().publishConfig(dataId, properties.getNacos().getGroup(), json);
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
            channelCode, targetCode, dataId, properties.getNacos().getGroup(), scope);
        return dataId;
    }

    /**
     * 灰度发布用独立 dataId：{@code <dataId>-tenant-<租户码>}。
     *
     * <p>客服端按自己的租户读对应 dataId，读不到再回落主 dataId——因此灰度版本只影响
     * 名单内的租户，其余租户继续用主 dataId 上的全量版本，不需要客服端理解"灰度"这个概念。</p>
     *
     * <p>多租户灰度会写多个 dataId，这里取第一个作为记录值；实际下发在
     * {@code ConfigRollbackService} 里逐租户循环。</p>
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
     * 把给定内容直接写到指定 dataId（灰度逐租户下发用）。
     *
     * <p>不留版本快照：灰度的版本记录由调用方在全部租户下发完之后统一记一条，
     * 每个租户各记一条只会把版本历史撑成噪音。</p>
     */
    public void publishToDataId(String dataId, String json) {
        try {
            boolean ok = configService().publishConfig(dataId, properties.getNacos().getGroup(), json);
            if (!ok) {
                throw new IllegalStateException("nacos publishConfig returned false, dataId=" + dataId);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("publish to dataId failed: " + dataId + ", " + e.getMessage(), e);
        }
    }

    /** 为可靠任务组装当前快照。快照只存于 worker 内存，任务表不复制密钥密文。 */
    public PreparedRuntimeConfig prepareTask(RuntimePublishTask task) {
        List<AiChannelBinding> bindings = channelBindingMapper.selectList(
            new LambdaQueryWrapper<AiChannelBinding>()
                .eq(AiChannelBinding::getAgentId, task.getTargetId())
                .eq(AiChannelBinding::getStatus, StatusFlags.ENABLED));
        if (CollectionUtils.isEmpty(bindings)) {
            throw new IllegalStateException("enabled channel binding not found for agent: " + task.getTargetId());
        }
        AiChannelBinding binding = bindings.get(0);
        AiAgent agent = agentMapper.selectById(task.getTargetId());
        if (agent == null) {
            throw new IllegalStateException("bound agent not found: " + task.getTargetId());
        }
        AiModelConfig primary = modelConfigAccess.findVisibleById(agent.getModelId());
        if (primary == null) {
            throw new IllegalStateException("primary model not found for agent: " + agent.getAgentCode());
        }
        assertConnectivity(primary);
        String revision = StringUtils.hasText(task.getRevision())
            ? task.getRevision() : UUID.randomUUID().toString();
        CustomerWorkRuntimeConfig payload = assemble(agent, primary);
        String publishedAt = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(task.getCreatedAtMs()), ZoneOffset.UTC).toString();
        enrichMetadata(payload, revision, publishedAt);
        return new PreparedRuntimeConfig(agent.getAgentCode(), binding.getChannelCode(),
            resolveTaskDataId(task.getTenantId()), properties.getNacos().getGroup(), revision,
            payload.getContentHash(), serialize(payload));
    }

    /** 任务 worker 复用既有 Nacos 发布入口，失败交给任务退避重试。 */
    public void publishPrepared(RuntimePublishTask task, PreparedRuntimeConfig prepared) {
        publishJsonToDataId(prepared.targetCode(), task.getTargetId(), prepared.json(), prepared.channelCode(),
            PublishScope.valueOf(task.getPublishScope()), task.getGrayTenants(),
            task.getSourceVersion(), task.getRemark(), prepared.dataId());
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
                                        String json) {
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
        String apiKey = cryptoUtil.decrypt(primary.getApiKey());
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
        CustomerWorkRuntimeConfig cfg = new CustomerWorkRuntimeConfig();
        cfg.setSchemaVersion(1);
        cfg.setPublishedAt(LocalDateTime.now().toString());
        cfg.setSystemPrompt(agent.getSystemPrompt());

        CustomerWorkRuntimeConfig.Model model = cfg.getModel();
        model.setProvider(primary.getProvider());
        model.setName(primary.getModel());
        model.setBaseUrl(primary.getBaseUrl());
        model.setApiKeyCipher(primary.getApiKey());   // 密文原样携带，不解密

        cfg.setFallback(assembleFallback(agent.getId(), primary.getId()));
        cfg.setMcpServers(assembleMcpServers(agent.getId()));

        CustomerWorkRuntimeConfig.Agent agentCfg = cfg.getAgent();
        agentCfg.setMaxIters(agent.getMaxIters());
        return cfg;
    }

    /** 内容摘要排除发布时间和 revision，用于崩溃重试时核对快照未漂移。 */
    private void enrichMetadata(CustomerWorkRuntimeConfig payload, String revision) {
        enrichMetadata(payload, revision, LocalDateTime.now().toString());
    }

    private void enrichMetadata(CustomerWorkRuntimeConfig payload, String revision, String publishedAt) {
        payload.setPublishedAt(null);
        payload.setRevision(null);
        payload.setContentHash(null);
        payload.setContentHash(sha256(serialize(payload)));
        payload.setPublishedAt(publishedAt);
        payload.setRevision(revision);
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
            fallback.setProvider(fb.getProvider());
            fallback.setName(fb.getModel());
            fallback.setBaseUrl(fb.getBaseUrl());
            fallback.setApiKeyCipher(fb.getApiKey());   // 密文原样携带
            return fallback;
        }
        return null;
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
            fillUrlAndHeaders(server, mcp.getMcpType(), mcp.getConfig());
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
            server.setHeaders(spec.headers());
        } catch (Exception e) {
            log.error("parse mcp config failed, code={}, mcpName={}", "RUNTIME-PUBLISH-MCP-PARSE-FAIL", server.getName(), e);
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
