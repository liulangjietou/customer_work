package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.config;

import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapService;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeGapRecorder;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbConnectionSettings;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGatewayProvider;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 后台侧的知识盲区埋点实现：把中间件路径的未命中写进客服端库的 {@code cw_knowledge_gap}。
 *
 * <p><b>为什么后台也要埋</b>：RAG 有两条互不相通的路径——客服端走工具调用
 * （{@code KnowledgeBaseTools}，早有埋点），后台工作台的智能体走
 * {@code KnowledgeRetrievalMiddleware} 自动注入。后者此前完全没有埋点，
 * 于是后台侧的知识未命中一条都统计不到，盲区看板长期只反映半条链路。</p>
 *
 * <p><b>埋点绝不打断对话</b>：跨库门面在客服端库不可达时会抛业务异常，
 * 这里必须整体兜住——统计失败最坏是少一条排行数据，不该让用户的问题因此答不出来。
 * 这与 {@link KnowledgeGapService} 内部"全程吞异常"的取舍一致，
 * 但门面的建连异常发生在它之外，得在这一层再兜一次。</p>
 *
 * <p>连接参数复用 {@link ContentGuardProperties}——盲区表与内容风控三表同在客服端库，
 * 再配一套连接参数只会多一处要同步维护的配置（与配额、评测、badcase 门面同一取舍）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminKnowledgeGapRecorder implements KnowledgeGapRecorder {

    private static final Logger log = LoggerFactory.getLogger(AdminKnowledgeGapRecorder.class);

    private static final String POOL_NAME = "knowledge-gap-pool";
    private static final int MAX_POOL_SIZE = 2;
    private static final String CODE_RECORD_FAIL = "GAP-RECORD-FAIL";

    private final ContentGuardProperties properties;
    private final CrossDbGatewayProvider<KnowledgeGapService> delegate;

    public AdminKnowledgeGapRecorder(ContentGuardProperties properties, AdminCrossDbTenantPlugins tenantPlugins) {
        this.properties = properties;
        this.delegate = CrossDbGateways.lazy(this::connectionSettings,
            KnowledgeGapGatewayFactory.MAPPER_CLASSES,
            KnowledgeGapGatewayFactory.MAPPER_XML_LOCATIONS,
            tenantPlugins::create,
            KnowledgeGapGatewayFactory::build);
    }

    @Override
    public void recordMiss(String sessionId, String question) {
        try {
            delegate.get().recordMiss(sessionId, question);
        } catch (Exception e) {
            // 旁路埋点：库不可达 / 建连失败都只记日志，绝不冒泡打断这一轮对话
            log.error("knowledge gap record failed, code={}, url={}", CODE_RECORD_FAIL, properties.jdbcUrl(), e);
        }
    }

    private CrossDbConnectionSettings connectionSettings() {
        return CrossDbConnectionSettings.builder(POOL_NAME, properties.jdbcUrl())
            .credentials(properties.getUsername(), properties.getPassword())
            .maximumPoolSize(MAX_POOL_SIZE)
            .build();
    }

    @PreDestroy
    public void close() {
        delegate.close();
    }
}
