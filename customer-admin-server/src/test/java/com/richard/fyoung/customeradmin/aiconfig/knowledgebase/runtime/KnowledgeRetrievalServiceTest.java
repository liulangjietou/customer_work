package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeBaseHttpGuard;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseTestResult;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiAgentKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeBaseEndpoint;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeGapRecorder;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalResult;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeSearchResult;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeRetrievalService} 单测：未绑定知识库零开销（不发请求）、召回为空不注入、
 * 注入内容追加在消息末尾（不能破坏 VibeCoding 的头部路径指引）、停用/未测试的知识库不参与检索、
 * 检索异常降级为原文不打断对话。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeRetrievalServiceTest {

    private static final String TEST_SECRET_KEY = "0123456789abcdef";
    private static final String AGENT_CODE = "customer-helper";

    private AiAgentMapper agentMapper;
    private AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper;
    private AiKnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeSearchClient searchClient;
    private KnowledgeRetrievalService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentKnowledgeBase.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiKnowledgeBase.class);
    }

    @BeforeEach
    void setUp() {
        agentMapper = mock(AiAgentMapper.class);
        agentKnowledgeBaseMapper = mock(AiAgentKnowledgeBaseMapper.class);
        knowledgeBaseMapper = mock(AiKnowledgeBaseMapper.class);
        searchClient = mock(KnowledgeSearchClient.class);
        service = new KnowledgeRetrievalService(agentMapper, agentKnowledgeBaseMapper, knowledgeBaseMapper,
            mock(AiKnowledgeBaseVersionMapper.class), new AesGcmCryptoUtil(TEST_SECRET_KEY), searchClient,
            mock(ManagedKnowledgeSearchService.class));

        AiAgent agent = new AiAgent();
        agent.setId(1L);
        agent.setAgentCode(AGENT_CODE);
        when(agentMapper.selectOne(any())).thenReturn(agent);
    }

    private void bindKnowledgeBase(AiKnowledgeBase knowledgeBase) {
        AiAgentKnowledgeBase relation = new AiAgentKnowledgeBase();
        relation.setAgentId(1L);
        relation.setKnowledgeBaseId(knowledgeBase.getId());
        when(agentKnowledgeBaseMapper.selectList(any())).thenReturn(List.of(relation));
        when(knowledgeBaseMapper.selectBatchIds(anyList())).thenReturn(List.of(knowledgeBase));
    }

    private AiKnowledgeBase usableKnowledgeBase() {
        AiKnowledgeBase kb = new AiKnowledgeBase();
        kb.setId(40L);
        kb.setKbName("产品知识库");
        kb.setBaseUrl("http://localhost:20002");
        kb.setAppId("app_123");
        kb.setApiKey(new AesGcmCryptoUtil(TEST_SECRET_KEY).encrypt("sk-1"));
        kb.setContentType("application/json");
        kb.setExtraHeaders("");
        kb.setTopN(5);
        kb.setScoreThreshold(BigDecimal.ZERO);
        kb.setStatus(1);
        kb.setTestStatus(ConnectivityTestStatus.SUCCESS);
        return kb;
    }

    @Test
    void retrieve_shouldNotSearch_whenAgentHasNoKnowledgeBase() {
        when(agentKnowledgeBaseMapper.selectList(any())).thenReturn(List.of());

        assertNull(service.retrieve(AGENT_CODE, "公积金怎么提取"), "未绑定知识库=不注入");
        verify(searchClient, never()).searchAllResult(anyList(), anyString());
    }

    @Test
    void retrieve_shouldNotSearch_whenAgentNotFound() {
        when(agentMapper.selectOne(any())).thenReturn(null);

        assertNull(service.retrieve(AGENT_CODE, "问题"));
        verify(searchClient, never()).searchAllResult(anyList(), anyString());
    }

    @Test
    void retrieve_shouldReturnOriginal_whenQueryOrAgentCodeBlank() {
        assertNull(service.retrieve("", "问题"));
        assertNull(service.retrieve(AGENT_CODE, "  "));
        verify(searchClient, never()).searchAllResult(anyList(), anyString());
    }

    @Test
    void retrieve_shouldNotInjectEmptyBlock_whenNothingRetrieved() {
        bindKnowledgeBase(usableKnowledgeBase());
        when(searchClient.searchAllResult(anyList(), anyString()))
            .thenReturn(new KnowledgeSearchResult(List.of(), true));

        assertNull(service.retrieve(AGENT_CODE, "公积金怎么提取"), "召回为空时绝不注入空标签污染上下文");
    }

    @Test
    void retrieve_shouldRenderSelfContainedBlock_withSourceAndScore() {
        bindKnowledgeBase(usableKnowledgeBase());
        when(searchClient.searchAllResult(anyList(), anyString())).thenReturn(new KnowledgeSearchResult(List.of(
            new KnowledgeNode("产品知识库", "提取需先满足封存满半年", new BigDecimal("0.183"), "doc-9", "chunk-3")), true));

        String result = service.retrieve(AGENT_CODE, "公积金怎么提取");

        // 返回的必须是"自包含的注入块"本身，不含用户提问原文——拼接由中间件挂成独立瞬态消息完成，
        // 本服务不再碰用户消息文本（那正是召回块曾被持久化进会话历史的根因）。
        assertTrue(result.startsWith("<retrieved_knowledge>"));
        assertTrue(result.endsWith("</retrieved_knowledge>"));
        assertFalse(result.contains("公积金怎么提取"), "块内不得夹带用户提问原文");
        assertTrue(result.contains("提取需先满足封存满半年"));
        assertTrue(result.contains("doc_id=doc-9"));
        assertTrue(result.contains("chunk_id=chunk-3"));
        assertTrue(result.contains("score=0.183"));
        assertTrue(result.contains("knowledge_base=产品知识库"));
    }

    @Test
    void retrieve_shouldSkipDisabledKnowledgeBase() {
        AiKnowledgeBase disabled = usableKnowledgeBase();
        disabled.setStatus(0);
        bindKnowledgeBase(disabled);

        // 停用后运行时立刻不再参与检索：可用端点被过滤空 → 直接零开销返回，一个请求都不发
        assertNull(service.retrieve(AGENT_CODE, "问题"));
        verify(searchClient, never()).searchAllResult(anyList(), anyString());
    }

    @Test
    void retrieve_shouldSkipUntestedKnowledgeBase() {
        AiKnowledgeBase untested = usableKnowledgeBase();
        untested.setTestStatus(ConnectivityTestStatus.FAILED);
        bindKnowledgeBase(untested);

        assertNull(service.retrieve(AGENT_CODE, "问题"));
        verify(searchClient, never()).searchAllResult(anyList(), anyString());
    }

    @Test
    void retrieve_shouldReturnNull_whenSearchThrows() {
        bindKnowledgeBase(usableKnowledgeBase());
        when(searchClient.searchAllResult(anyList(), anyString())).thenThrow(new IllegalStateException("boom"));

        assertNull(assertDoesNotThrow(() -> service.retrieve(AGENT_CODE, "问题")),
            "检索异常必须降级为不注入，绝不打断对话");
    }

    /** 现有用例分开验证“服务吞异常”和“中间件识别抛异常”，没有接起真实两层。 */
    @Test
    void injectionShouldNotClassifyAHandledRetrievalFailureAsKnowledgeGap() {
        bindKnowledgeBase(usableKnowledgeBase());
        when(searchClient.searchAllResult(anyList(), anyString()))
            .thenThrow(new IllegalStateException("retrieval unavailable"));
        assertEquals(0, missesAfterInjection(), "检索故障不属于缺失知识，不能写入未命中排行");
    }

    @Test
    void injectionShouldNotClassifyUnconfiguredKnowledgeAsAnObservedMiss() {
        when(agentKnowledgeBaseMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, missesAfterInjection(), "尚未配置检索目标，不应制造已执行检索未命中的事实");
    }

    @Test
    void injectionShouldStillRecordAnActualEmptyRetrieval() {
        bindKnowledgeBase(usableKnowledgeBase());
        when(searchClient.searchAllResult(anyList(), anyString()))
            .thenReturn(new KnowledgeSearchResult(List.of(), true));
        assertEquals(1, missesAfterInjection());
    }

    @Test
    void retrieveShouldKeepPartialHitsMarkedDegraded() {
        bindKnowledgeBase(usableKnowledgeBase());
        when(searchClient.searchAllResult(anyList(), anyString())).thenReturn(new KnowledgeSearchResult(
            List.of(new KnowledgeNode("产品知识库", "可核对的退款流程", BigDecimal.ONE, "refund", "c1")), false));

        KnowledgeRetrievalResult result = service.retrieveResult(AGENT_CODE, "退款流程", null);

        assertEquals(KnowledgeRetrievalResult.Status.DEGRADED, result.status());
        assertTrue(result.block().contains("可核对的退款流程"), "部分故障不能丢弃其他外部知识库的有效召回");
        assertTrue(result.block().contains("doc_id=refund"));
    }

    /** 接起实际 HTTP 执行核心，避免只验证“客户端抛异常”的桩而漏掉内部降级。 */
    @ParameterizedTest
    @CsvSource({"503,0", "200,1"})
    void injectionShouldUseActualHttpOutcome(int status, int expectedMisses) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/api/v1/knowledge/search", exchange -> {
            requests.incrementAndGet();
            String payload = status == 200 ? "{\"code\":\"OK\",\"data\":{\"nodes\":[]}}"
                : "{\"code\":\"UNAVAILABLE\"}";
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        try {
            AiKnowledgeBase knowledgeBase = usableKnowledgeBase();
            knowledgeBase.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            bindKnowledgeBase(knowledgeBase);
            AdminRagProperties properties = new AdminRagProperties();
            KnowledgeSearchClient httpClient = new KnowledgeSearchClient(
                new KnowledgeBaseHttpGuard(properties), properties);
            service = new KnowledgeRetrievalService(agentMapper, agentKnowledgeBaseMapper,
                knowledgeBaseMapper, mock(AiKnowledgeBaseVersionMapper.class),
                new AesGcmCryptoUtil(TEST_SECRET_KEY), httpClient,
                mock(ManagedKnowledgeSearchService.class));
            assertEquals(expectedMisses, missesAfterInjection(), "统计应以真实 HTTP 执行结果为准");
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private int missesAfterInjection() {
        AtomicInteger misses = new AtomicInteger();
        KnowledgeGapRecorder recorder = (session, question) -> misses.incrementAndGet();
        var middleware = new KnowledgeRetrievalMiddleware(service, AGENT_CODE, recorder);
        var input = new ReasoningInput(List.of(Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text("退款需要哪些凭证").build()).build()), null, null);
        middleware.onReasoning(null, RuntimeContext.builder().userId("42").sessionId("s1").build(),
            input, next -> {
                assertEquals(input, next, "检索无结果时保持原模型输入，检索故障不能中断对话");
                return Flux.empty();
            }).blockLast(Duration.ofSeconds(5));
        return misses.get();
    }

    @Test
    void retrieve_shouldPassDecryptedApiKeyToClient() {
        bindKnowledgeBase(usableKnowledgeBase());
        when(searchClient.searchAllResult(anyList(), anyString()))
            .thenReturn(new KnowledgeSearchResult(List.of(), true));

        service.retrieve(AGENT_CODE, "问题");

        verify(searchClient).searchAllResult(List.of(new KnowledgeBaseEndpoint(40L, "产品知识库",
            "http://localhost:20002", "app_123", "sk-1", "application/json", "", 5, BigDecimal.ZERO)), "问题");
    }
}
