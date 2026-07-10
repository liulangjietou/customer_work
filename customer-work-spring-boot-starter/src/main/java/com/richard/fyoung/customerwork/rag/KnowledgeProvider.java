package com.richard.fyoung.customerwork.rag;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.integration.dify.DifyKnowledge;
import io.agentscope.core.rag.integration.dify.DifyRAGConfig;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.InMemoryStore;
import jakarta.annotation.PostConstruct;
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

    /**
     * 启动期在主线程上预热一次，把 {@link #build()} 里那个 {@code .block()} 调用固定挪到应用启动
     * 阶段执行——不这样做的话，{@link #get()} 的懒加载首次调用发生在谁身上完全看运气：如果调用方
     * 是 WebFlux 反应式应用（比如 {@code customer-work-downstream-app} 把 starter 当依赖引入时），
     * 处理第一个真实请求的线程是 Reactor 的 {@code reactor-http-nio} 事件循环线程，Reactor 3.4+
     * 禁止在这类线程上调 {@code block()}，会直接抛
     * {@code IllegalStateException: block()/blockFirst()/blockLast() are blocking, which is not
     * supported in thread reactor-http-nio-*}——命中与否取决于"第一次访问 RAG 的请求恰好落在哪个
     * 线程上"，纯属偶然，不改代码只能听天由命。启动期预热后 {@link #cached} 已经填好，运行期
     * {@link #get()} 只读缓存，不会再触碰 {@link #build()}，从根上让"在哪个线程调用"这个问题不存在。
     */
    @PostConstruct
    void warmUp() {
        get();
    }

    /** 获取共享 Knowledge 实例（懒加载，一次构建多会话复用；正常情况下 {@link #warmUp()} 已经填好缓存）。 */
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
        switch (provider == null ? "memory" : provider.toLowerCase()) {
            case "simple":
                return buildSimple();
            case "bailian":
                return buildBailian();
            case "dify":
                return buildDify();
            default:
                InMemoryKeywordKnowledge knowledge =
                    new InMemoryKeywordKnowledge(properties.getRag().getTopK());
                knowledge.addTexts(KNOWLEDGE_DOCS).block();
                log.info("[RAG] 使用内存知识库，预置文档 {} 条", KNOWLEDGE_DOCS.size());
                return knowledge;
        }
    }

    /** 真实 Embedding 向量 RAG：百炼 Embedding + 内存向量库（语义检索）。 */
    private Knowledge buildSimple() {
        CustomerWorkProperties.Model m = properties.getModel();
        DashScopeTextEmbedding embedding = DashScopeTextEmbedding.builder()
            .apiKey(m.getApiKey())
            .modelName(m.getEmbeddingName())
            .dimensions(properties.getRag().getSimple().getDimensions())
            .build();
        log.info("[RAG] 使用 SimpleKnowledge（百炼 Embedding {} + 内存向量库）", m.getEmbeddingName());
        // 文档灌库由运维侧通过 addDocuments 异步完成（嵌入需调用 Embedding 服务，避免启动期阻塞）
        return SimpleKnowledge.builder()
            .embeddingModel(embedding)
            .embeddingStore(InMemoryStore.builder()
                .dimensions(properties.getRag().getSimple().getDimensions())
                .build())
            .build();
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

    /** Dify 知识库（外部 Dify 服务）。 */
    private Knowledge buildDify() {
        CustomerWorkProperties.Rag.Dify d = properties.getRag().getDify();
        DifyRAGConfig cfg = DifyRAGConfig.builder()
            .apiKey(d.getApiKey())
            .apiBaseUrl(d.getApiBaseUrl())
            .datasetId(d.getDatasetId())
            .topK(properties.getRag().getTopK())
            .enableRerank(d.isEnableRerank())
            .build();
        log.info("[RAG] 使用 Dify 知识库 datasetId={}", d.getDatasetId());
        return DifyKnowledge.builder().config(cfg).build();
    }
}
