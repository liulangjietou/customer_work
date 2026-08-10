package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

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

    public RuntimeConfigApplier(CustomerWorkProperties properties,
                                ModelConfig modelConfig,
                                MutableDelegatingModel mutableModel,
                                NacosPromptService nacosPromptService,
                                CustomerServiceService customerServiceService) {
        this.properties = properties;
        this.modelConfig = modelConfig;
        this.mutableModel = mutableModel;
        this.nacosPromptService = nacosPromptService;
        this.customerServiceService = customerServiceService;
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
        try {
            validate(dto);
            // 先在临时配置上构建新链（可能抛错：厂商不支持 / 密钥缺失等），成功后才提交
            CustomerWorkProperties.Model staged = stageModelCfg(dto, primaryApiKey, fallbackApiKey);
            Model newChain = modelConfig.buildChain(staged);

            // ---- 提交阶段：以下步骤均为不抛错的赋值/替换，保证全有或全无 ----
            copyModelCfg(staged, properties.getModel());
            applyMcp(dto);
            applyAgent(dto);
            nacosPromptService.updatePrompt(dto.getSystemPrompt());
            mutableModel.swap(newChain);
            customerServiceService.flushHotAgents();

            log.info("[hot-config] runtime config applied, schemaVersion={}, provider={}, model={}, mcpServers={}",
                dto.getSchemaVersion(), staged.getProvider(), staged.getName(),
                dto.getMcpServers() == null ? 0 : dto.getMcpServers().size());
            return true;
        } catch (Exception e) {
            log.error("runtime config apply failed, keep old config, code={}", CODE_APPLY_FAIL, e);
            return false;
        }
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
    }

    /** 用当前配置打底、以 DTO 覆盖，产出一份用于构建新链的临时模型配置（不改动 properties）。 */
    private CustomerWorkProperties.Model stageModelCfg(CustomerWorkRuntimeConfig dto,
                                                       String primaryApiKey, String fallbackApiKey) {
        CustomerWorkProperties.Model base = properties.getModel();
        CustomerWorkProperties.Model staged = new CustomerWorkProperties.Model();
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
        CustomerWorkProperties.Model.Fallback target = staged.getFallback();
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
        CustomerWorkProperties.Model.Retry retry = staged.getRetry();
        if (rt != null && rt.isEnabled()) {
            retry.setEnabled(true);
            retry.setMaxAttempts(rt.getMaxAttempts());
            retry.setBackoffMs(rt.getBackoffMs());
        } else {
            retry.setEnabled(false);
        }
        return staged;
    }

    /** 复制模型链构建相关字段（scalar + fallback/retry 内部字段），不复制 costControl 等无关项。 */
    private void copyModelCfg(CustomerWorkProperties.Model from, CustomerWorkProperties.Model to) {
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
    }

    /** 回写 MCP 服务列表：空列表表示清空接入（enabled=false）。 */
    private void applyMcp(CustomerWorkRuntimeConfig dto) {
        List<CustomerWorkRuntimeConfig.McpServer> servers = dto.getMcpServers();
        CustomerWorkProperties.Mcp mcp = properties.getMcp();
        if (CollectionUtils.isEmpty(servers)) {
            mcp.setEnabled(false);
            mcp.setServers(new ArrayList<>());
            return;
        }
        List<CustomerWorkProperties.Mcp.Server> mapped = new ArrayList<>();
        for (CustomerWorkRuntimeConfig.McpServer s : servers) {
            CustomerWorkProperties.Mcp.Server server = new CustomerWorkProperties.Mcp.Server();
            server.setName(s.getName());
            server.setUrl(s.getUrl());
            server.setTransport(StringUtils.hasText(s.getTransport()) ? s.getTransport() : "sse");
            server.setHeaders(s.getHeaders());
            mapped.add(server);
        }
        mcp.setEnabled(true);
        mcp.setServers(mapped);
    }

    /** 回写 Agent maxIters（DTO 未提供则不动）。 */
    private void applyAgent(CustomerWorkRuntimeConfig dto) {
        if (dto.getAgent() != null && dto.getAgent().getMaxIters() != null) {
            properties.getAgent().setMaxIters(dto.getAgent().getMaxIters());
        }
    }
}
