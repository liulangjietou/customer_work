package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.richard.fyoung.customerwork.infra.config.properties.MemoryProperties;

/**
 * 长期记忆提供方（对应特性「长期记忆」，支持实现热切换）。
 *
 * <p>按 {@code customer-work.memory.provider} 为每个租户创建长期记忆：</p>
 * <ul>
 *   <li><b>memory</b>：内置 {@link InMemoryLongTermMemory}（L2 语义召回 + L3 事实日志），开箱即用、可单测；</li>
 *   <li><b>bailian</b>：阿里云百炼长期记忆 {@link BailianLongTermMemory}，托管的画像抽取与语义召回，
 *       以 userId=租户 ID 做多租户隔离。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Component
public class LongTermMemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryProvider.class);

    private final CustomerWorkProperties properties;
    private final LongTermMemoryStore store;
    private final FactLog factLog;

    public LongTermMemoryProvider(CustomerWorkProperties properties,
                                  LongTermMemoryStore store,
                                  FactLog factLog) {
        this.properties = properties;
        this.store = store;
        this.factLog = factLog;
    }

    /** 为指定租户创建长期记忆实例。 */
    public LongTermMemory create(String tenantId) {
        MemoryProperties cfg = properties.getMemory();
        switch (cfg.getProvider() == null ? "memory" : cfg.getProvider().toLowerCase()) {
            case "bailian":
                return buildBailian(tenantId, cfg);
            case "mem0":
                return buildMem0(tenantId, cfg);
            case "reme":
                return buildReMe(tenantId, cfg);
            default:
                return new InMemoryLongTermMemory(store, factLog, tenantId, cfg.getRetrieveTopK());
        }
    }

    private LongTermMemory buildMem0(String tenantId, MemoryProperties cfg) {
        MemoryProperties.Mem0 m = cfg.getMem0();
        Mem0LongTermMemory.Builder builder = Mem0LongTermMemory.builder()
            .userId(tenantId)
            .agentName(m.getAgentName())
            .apiKey(m.getApiKey())
            .apiType("self_hosted".equalsIgnoreCase(m.getApiType())
                ? Mem0ApiType.SELF_HOSTED : Mem0ApiType.PLATFORM);
        if (StringUtils.hasText(m.getApiBaseUrl())) {
            builder.apiBaseUrl(m.getApiBaseUrl());
        }
        log.info("[LTM] 使用 Mem0 长期记忆 userId(tenant)={} apiType={}", tenantId, m.getApiType());
        return builder.build();
    }

    private LongTermMemory buildReMe(String tenantId, MemoryProperties cfg) {
        MemoryProperties.ReMe r = cfg.getReme();
        ReMeLongTermMemory.Builder builder = ReMeLongTermMemory.builder().userId(tenantId);
        if (StringUtils.hasText(r.getApiBaseUrl())) {
            builder.apiBaseUrl(r.getApiBaseUrl());
        }
        log.info("[LTM] 使用 ReMe 长期记忆 userId(tenant)={}", tenantId);
        return builder.build();
    }

    private LongTermMemory buildBailian(String tenantId, MemoryProperties cfg) {
        MemoryProperties.Bailian b = cfg.getBailian();
        // API Key 优先用专配，否则复用模型层的百炼 Key
        String apiKey = StringUtils.hasText(b.getApiKey())
            ? b.getApiKey() : properties.getModel().getApiKey();

        BailianLongTermMemory.Builder builder = BailianLongTermMemory.builder()
            .apiKey(apiKey)
            .userId(tenantId)              // 多租户隔离：每租户独立记忆空间
            .memoryLibraryId(b.getMemoryLibraryId())
            .topK(b.getTopK());
        if (StringUtils.hasText(b.getApiBaseUrl())) {
            builder.apiBaseUrl(b.getApiBaseUrl());
        }
        if (StringUtils.hasText(b.getProjectId())) {
            builder.projectId(b.getProjectId());
        }
        log.info("[LTM] 使用百炼长期记忆 userId(tenant)={} memoryLibraryId={}",
            tenantId, b.getMemoryLibraryId());
        return builder.build();
    }
}
