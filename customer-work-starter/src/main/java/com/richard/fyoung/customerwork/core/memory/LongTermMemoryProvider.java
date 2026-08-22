package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.MemoryProperties;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 长期记忆提供方（对应特性「长期记忆」，支持实现热切换）。
 *
 * <p>按 {@code customer-work.memory.provider} 为每个主体创建长期记忆：</p>
 * <ul>
 *   <li><b>memory</b>：内置 {@link InMemoryLongTermMemory}（L2 语义召回 + L3 事实日志），开箱即用、可单测；</li>
 *   <li><b>bailian</b>：阿里云百炼长期记忆 {@link BailianLongTermMemory}，托管的画像抽取与语义召回，
 *       以去标识化主体键作为 userId，按租户、主体类型、主体与 Agent 四维隔离。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Component
public class LongTermMemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryProvider.class);

    private final CustomerWorkProperties properties;
    private final LongTermMemoryStore store;
    private final FactLog factLog;
    private final MemoryConsentService consentService;

    @Autowired
    public LongTermMemoryProvider(CustomerWorkProperties properties,
                                  LongTermMemoryStore store,
                                  FactLog factLog,
                                  MemoryConsentService consentService) {
        this.properties = properties;
        this.store = store;
        this.factLog = factLog;
        this.consentService = consentService;
    }

    /** 兼容未启用同意治理的纯单元测试。 */
    public LongTermMemoryProvider(CustomerWorkProperties properties,
                                  LongTermMemoryStore store,
                                  FactLog factLog) {
        this(properties, store, factLog, null);
    }

    /** 为指定主体创建长期记忆实例。 */
    public LongTermMemory create(MemorySubjectKey subject) {
        MemoryProperties cfg = properties.getMemory();
        LongTermMemory delegate;
        switch (cfg.getProvider() == null ? "memory" : cfg.getProvider().toLowerCase()) {
            case "bailian":
                delegate = buildBailian(subject.providerUserId(), cfg);
                break;
            case "mem0":
                delegate = buildMem0(subject.providerUserId(), cfg);
                break;
            case "reme":
                delegate = buildReMe(subject.providerUserId(), cfg);
                break;
            default:
                delegate = new InMemoryLongTermMemory(store, factLog, subject.scopeId(), cfg.getRetrieveTopK());
                break;
        }
        if (!cfg.isConsentRequired()) {
            return delegate;
        }
        if (consentService == null) {
            throw new IllegalStateException("memory consent service is required");
        }
        return new GovernedLongTermMemory(delegate, subject, consentService);
    }

    /** 旧调用仅用于兼容测试；新生产链路必须传显式主体键。 */
    public LongTermMemory create(String legacyScope) {
        return create(new MemorySubjectKey("default", MemorySubjectType.SESSION,
            legacyScope, MemorySubjectResolver.CUSTOMER_SERVICE_AGENT));
    }

    private LongTermMemory buildMem0(String subjectKey, MemoryProperties cfg) {
        MemoryProperties.Mem0 m = cfg.getMem0();
        Mem0LongTermMemory.Builder builder = Mem0LongTermMemory.builder()
            .userId(subjectKey)
            .agentName(m.getAgentName())
            .apiKey(m.getApiKey())
            .apiType("self_hosted".equalsIgnoreCase(m.getApiType())
                ? Mem0ApiType.SELF_HOSTED : Mem0ApiType.PLATFORM);
        if (StringUtils.hasText(m.getApiBaseUrl())) {
            builder.apiBaseUrl(m.getApiBaseUrl());
        }
        log.info("[LTM] 使用 Mem0 长期记忆 subjectKey={} apiType={}", subjectKey, m.getApiType());
        return builder.build();
    }

    private LongTermMemory buildReMe(String subjectKey, MemoryProperties cfg) {
        MemoryProperties.ReMe r = cfg.getReme();
        ReMeLongTermMemory.Builder builder = ReMeLongTermMemory.builder().userId(subjectKey);
        if (StringUtils.hasText(r.getApiBaseUrl())) {
            builder.apiBaseUrl(r.getApiBaseUrl());
        }
        log.info("[LTM] 使用 ReMe 长期记忆 subjectKey={}", subjectKey);
        return builder.build();
    }

    private LongTermMemory buildBailian(String subjectKey, MemoryProperties cfg) {
        MemoryProperties.Bailian b = cfg.getBailian();
        // API Key 优先用专配，否则复用模型层的百炼 Key
        String apiKey = StringUtils.hasText(b.getApiKey())
            ? b.getApiKey() : properties.getModel().getApiKey();

        BailianLongTermMemory.Builder builder = BailianLongTermMemory.builder()
            .apiKey(apiKey)
            .userId(subjectKey)
            .memoryLibraryId(b.getMemoryLibraryId())
            .topK(b.getTopK());
        if (StringUtils.hasText(b.getApiBaseUrl())) {
            builder.apiBaseUrl(b.getApiBaseUrl());
        }
        if (StringUtils.hasText(b.getProjectId())) {
            builder.projectId(b.getProjectId());
        }
        log.info("[LTM] 使用百炼长期记忆 subjectKey={} memoryLibraryId={}",
            subjectKey, b.getMemoryLibraryId());
        return builder.build();
    }
}
