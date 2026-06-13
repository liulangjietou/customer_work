package com.example.customerwork.rag;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 知识库提供方（对应特性「RAG」，支持实现热切换）。
 *
 * <p>按 {@code customer-work.rag.provider} 选择：</p>
 * <ul>
 *   <li><b>memory</b>：内置 {@link InMemoryKeywordKnowledge}，灌入预置企业政策文档，开箱即用、可单测；</li>
 *   <li><b>bailian</b>：阿里云百炼企业知识库 {@link BailianKnowledge}，具备真正的语义检索与重排，
 *       数据在百炼侧维护（不在本地灌库）。</li>
 * </ul>
 *
 * <p>对调用方（{@code CustomerServiceAgentFactory}）只暴露统一的 {@link Knowledge}，切换实现零代码改动。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class KnowledgeProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProvider.class);

    /** 内存版预置知识：企业政策文档（生产中由真实知识库提供）。 */
    private static final List<String> KNOWLEDGE_DOCS = List.of(
        "支持七天无理由退货，商品需保持完好、不影响二次销售；定制类、生鲜类除外。（来源：《售后服务政策》第 3 条）",
        "支持开具电子普通发票与增值税专用发票，可在订单详情页自助申请，1-3 个工作日开具。（来源：《发票管理规则》第 1 条）",
        "单笔订单满 99 元包邮，偏远地区除外；退货运费由责任方承担。（来源：《运费说明》第 2 条）",
        "大额退款（单笔超过 1000 元）需人工坐席复核后处理，预计 1 个工作日内完成。（来源：《资金安全规范》第 5 条）");

    private final CustomerWorkProperties properties;
    private volatile Knowledge cached;

    public KnowledgeProvider(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    /** 获取共享 Knowledge 实例（懒加载，一次构建多会话复用）。 */
    public Knowledge get() {
        if (cached == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = build();
                }
            }
        }
        return cached;
    }

    private Knowledge build() {
        String provider = properties.getRag().getProvider();
        if ("bailian".equalsIgnoreCase(provider)) {
            return buildBailian();
        }
        InMemoryKeywordKnowledge knowledge = new InMemoryKeywordKnowledge(properties.getRag().getTopK());
        knowledge.addTexts(KNOWLEDGE_DOCS).block();
        log.info("[RAG] 使用内存知识库，预置文档 {} 条", KNOWLEDGE_DOCS.size());
        return knowledge;
    }

    private Knowledge buildBailian() {
        CustomerWorkProperties.Rag.Bailian b = properties.getRag().getBailian();
        BailianConfig.Builder cfg = BailianConfig.builder()
            .accessKeyId(b.getAccessKeyId())
            .accessKeySecret(b.getAccessKeySecret())
            .workspaceId(b.getWorkspaceId())
            .indexId(b.getIndexId())
            .enableReranking(b.isEnableReranking());
        if (b.getEndpoint() != null && !b.getEndpoint().isBlank()) {
            cfg.endpoint(b.getEndpoint());
        }
        log.info("[RAG] 使用百炼企业知识库 indexId={}", b.getIndexId());
        return BailianKnowledge.builder().config(cfg.build()).build();
    }
}
