package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentBackupModel;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customerwork.config.CustomerWorkRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 客服机器人运行时配置发布器（仿 {@code NacosSkillPublisher}）。
 *
 * <p>读渠道绑定 → 组装 {@link CustomerWorkRuntimeConfig}（API Key 密文原样携带、兜底取备用模型第一个）→
 * 发布前<b>强制连通性探测</b>（不通过则拒绝发布，防坏配置下发）→ publishConfig 到 Nacos，8080 监听热生效。</p>
 *
 * <p>默认关闭（{@code admin.runtime-publish.nacos.enabled=false}）：关闭时所有发布方法直接跳过，不影响
 * 任何现有后台链路。自动触发（智能体/模型保存后）发布失败仅 {@code log.error}，可在渠道绑定页手动重发。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class CustomerWorkConfigPublisher {

    private static final Logger log = LoggerFactory.getLogger(CustomerWorkConfigPublisher.class);

    private static final String CODE_PUBLISH_FAIL = "RUNTIME-PUBLISH-FAIL";
    private static final String CODE_PROBE_BLOCK = "RUNTIME-PUBLISH-PROBE-BLOCK";
    private static final int STATUS_ENABLED = 1;
    private static final String MCP_TYPE_HTTP = "http";
    private static final String MCP_TYPE_SSE = "sse";
    private static final String TRANSPORT_STREAMABLE_HTTP = "streamable-http";

    private final AiChannelBindingMapper channelBindingMapper;
    private final AiAgentMapper agentMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final AiAgentBackupModelMapper agentBackupModelMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiMcpMapper mcpMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AdminModelFactory modelFactory;
    private final RuntimePublishProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile ConfigService configService;

    public CustomerWorkConfigPublisher(AiChannelBindingMapper channelBindingMapper, AiAgentMapper agentMapper,
                                       AiModelConfigMapper modelConfigMapper,
                                       AiAgentBackupModelMapper agentBackupModelMapper,
                                       AiAgentMcpMapper agentMcpMapper, AiMcpMapper mcpMapper,
                                       AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                                       RuntimePublishProperties properties) {
        this.channelBindingMapper = channelBindingMapper;
        this.agentMapper = agentMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.mcpMapper = mcpMapper;
        this.cryptoUtil = cryptoUtil;
        this.modelFactory = modelFactory;
        this.properties = properties;
    }

    /** 发布能力是否启用（供 Controller 手动发布前门禁判断）。 */
    public boolean isEnabled() {
        return properties.getNacos().isEnabled();
    }

    /**
     * 自动触发发布：某智能体被改动后，若它命中启用中的渠道绑定则重新下发运行时配置。
     * 失败仅记 error（不抛，不阻断保存链路），可在渠道绑定页手动重发。
     *
     * <p>调用点多在 {@code @Transactional} 写方法内：实际发布（含秒级连通性探测 + Nacos 往返）注册到
     * <b>事务提交后</b>执行（{@link #runAfterCommitOrNow}），避免长事务占连接，且事务回滚时不下发已回滚的配置。</p>
     */
    public void publishForAgentId(Long agentId) {
        if (!isEnabled() || agentId == null) {
            return;
        }
        runAfterCommitOrNow(() -> doPublishForAgentId(agentId));
    }

    /**
     * 自动触发发布：某模型配置被改动后，重新下发所有「以该模型为主模型或备用模型」且命中绑定的智能体配置。
     * 与 {@code ModelConfigService#evictAgentsReferencingModel} 的失效范围对齐。失败仅记 error。
     * 事务语义同 {@link #publishForAgentId}（提交后执行）。
     */
    public void publishForModelId(Long modelId) {
        if (!isEnabled() || modelId == null) {
            return;
        }
        runAfterCommitOrNow(() -> doPublishForModelId(modelId));
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
                .eq(AiChannelBinding::getStatus, STATUS_ENABLED));
        for (AiChannelBinding binding : bindings) {
            try {
                doPublish(binding);
            } catch (Exception e) {
                log.error("auto publish runtime config failed, code={}, channel={}, agentId={}",
                    CODE_PUBLISH_FAIL, binding.getChannelCode(), agentId, e);
            }
        }
    }

    /** 实际按模型发布（已在事务外/提交后执行）。 */
    private void doPublishForModelId(Long modelId) {
        List<Long> agentIds = new ArrayList<>(agentMapper.selectList(
                new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getModelId, modelId))
            .stream().map(AiAgent::getId).toList());
        agentBackupModelMapper.selectList(
                new LambdaQueryWrapper<AiAgentBackupModel>().eq(AiAgentBackupModel::getModelId, modelId))
            .stream().map(AiAgentBackupModel::getAgentId).forEach(id -> {
                if (!agentIds.contains(id)) {
                    agentIds.add(id);
                }
            });
        for (Long agentId : agentIds) {
            doPublishForAgentId(agentId);
        }
    }

    /**
     * 手动重发（渠道绑定页「重新发布」按钮）：探测不过或发布失败时抛异常，由 Controller 转成业务错误码。
     *
     * @return 发布使用的 dataId（便于前端提示）
     */
    public String republishByChannel(String channelCode) {
        AiChannelBinding binding = channelBindingMapper.selectOne(
            new LambdaQueryWrapper<AiChannelBinding>().eq(AiChannelBinding::getChannelCode, channelCode));
        if (binding == null) {
            throw new IllegalArgumentException("channel binding not found: " + channelCode);
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
        AiModelConfig primary = modelConfigMapper.selectById(agent.getModelId());
        if (primary == null) {
            throw new IllegalStateException("primary model not found for agent: " + agent.getAgentCode());
        }
        // 发布门禁：强制连通性探测（解密密钥仅用于探测，不进入下发载荷）
        assertConnectivity(primary);

        CustomerWorkRuntimeConfig payload = assemble(agent, primary);
        String json = serialize(payload);
        String dataId = properties.getNacos().getDataId();
        try {
            boolean ok = configService().publishConfig(dataId, properties.getNacos().getGroup(), json);
            if (!ok) {
                throw new IllegalStateException("nacos publishConfig returned false");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("publish runtime config to nacos failed: " + e.getMessage(), e);
        }
        log.info("runtime config published, channel={}, agent={}, dataId={}, group={}",
            binding.getChannelCode(), agent.getAgentCode(), dataId, properties.getNacos().getGroup());
        return dataId;
    }

    /** 连通性门禁：解密主模型密钥后走既有探测协议，非成功即拒绝发布。 */
    private void assertConnectivity(AiModelConfig primary) {
        String apiKey = cryptoUtil.decrypt(primary.getApiKey());
        ModelTestResult result = modelFactory.testConnectivity(
            primary.getProvider(), primary.getBaseUrl(), apiKey, primary.getModel());
        if (result.testStatus() != ModelTestResult.STATUS_SUCCESS) {
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
            AiModelConfig fb = modelConfigMapper.selectById(backup.getModelId());
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
        for (AiMcp mcp : mcpMapper.selectBatchIds(mcpIds)) {
            if (mcp.getStatus() != null && mcp.getStatus() != STATUS_ENABLED) {
                continue;
            }
            String transport = resolveTransport(mcp.getMcpType());
            if (transport == null) {
                continue;   // stdio 等 8080 不支持的传输类型跳过
            }
            CustomerWorkRuntimeConfig.McpServer server = new CustomerWorkRuntimeConfig.McpServer();
            server.setName(mcp.getMcpName());
            server.setTransport(transport);
            fillUrlAndHeaders(server, mcp.getConfig());
            if (StringUtils.hasText(server.getUrl())) {
                servers.add(server);
            }
        }
        return servers;
    }

    private String resolveTransport(String mcpType) {
        if (MCP_TYPE_HTTP.equalsIgnoreCase(mcpType)) {
            return TRANSPORT_STREAMABLE_HTTP;
        }
        if (MCP_TYPE_SSE.equalsIgnoreCase(mcpType)) {
            return MCP_TYPE_SSE;
        }
        return null;
    }

    /** 解析 ai_mcp.config JSON 的 url 与 headers（与 AdminMcpFactory 的取值方式一致）。 */
    private void fillUrlAndHeaders(CustomerWorkRuntimeConfig.McpServer server, String config) {
        if (!StringUtils.hasText(config)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(config);
            server.setUrl(node.path("url").asText(null));
            JsonNode headersNode = node.path("headers");
            if (headersNode.isObject() && !headersNode.isEmpty()) {
                server.setHeaders(objectMapper.convertValue(headersNode, new TypeReference<Map<String, String>>() {}));
            }
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
