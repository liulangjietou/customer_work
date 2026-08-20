package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseOptionVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseTestResult;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeBaseVO;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiAgentKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiAgentKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeBaseEndpoint;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeBaseService} 单测：AppKey 加密落库/掩码回显/"留空=不改"、名称唯一、
 * <b>保存门禁</b>（实测失败阻止保存 / 只改名不重测）、被引用时拒删、连通性测试落库、下拉选项过滤。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeBaseServiceTest {

    private static final String TEST_SECRET_KEY = "0123456789abcdef";

    private AiKnowledgeBaseMapper knowledgeBaseMapper;
    private AiAgentKnowledgeBaseMapper agentKnowledgeBaseMapper;
    private KnowledgeSearchClient searchClient;
    private KnowledgeBaseService service;

    /**
     * MyBatis-Plus 的 Lambda 包装器依赖实体 TableInfo 缓存，纯 Mockito 单测无容器，需手动注册一次，
     * 否则 LambdaQueryWrapper 会抛 "can not find lambda cache"。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiKnowledgeBase.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentKnowledgeBase.class);
    }

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(AiKnowledgeBaseMapper.class);
        agentKnowledgeBaseMapper = mock(AiAgentKnowledgeBaseMapper.class);
        searchClient = mock(KnowledgeSearchClient.class);
        service = new KnowledgeBaseService(knowledgeBaseMapper, agentKnowledgeBaseMapper,
            new AesGcmCryptoUtil(TEST_SECRET_KEY), searchClient, new AdminRagProperties());
        // 默认探测成功（个别用例覆写为失败）
        when(searchClient.searchOne(any(KnowledgeBaseEndpoint.class), anyString()))
            .thenReturn(List.of(new KnowledgeNode("kb", "片段", new BigDecimal("0.18"), "d1", "c1")));
    }

    private KnowledgeBaseSaveRequest request(String kbName, String apiKey) {
        return new KnowledgeBaseSaveRequest(kbName, "http://localhost:20002", "app_123", apiKey,
            null, null, null, null, 1, "备注");
    }

    private AiKnowledgeBase existing(Long id, String plainApiKey) {
        AiKnowledgeBase kb = new AiKnowledgeBase();
        kb.setId(id);
        kb.setKbName("产品知识库");
        kb.setBaseUrl("http://localhost:20002");
        kb.setAppId("app_123");
        kb.setApiKey(new AesGcmCryptoUtil(TEST_SECRET_KEY).encrypt(plainApiKey));
        kb.setContentType("application/json");
        kb.setExtraHeaders("");
        kb.setTopN(5);
        kb.setScoreThreshold(BigDecimal.ZERO);
        kb.setStatus(1);
        kb.setTestStatus(ConnectivityTestStatus.SUCCESS);
        return kb;
    }

    // ---- 加密 / 掩码 / 留空不改 ----

    @Test
    void create_shouldEncryptApiKey_notStorePlainText() {
        ArgumentCaptor<AiKnowledgeBase> captor = ArgumentCaptor.forClass(AiKnowledgeBase.class);

        service.create(request("产品知识库", "sk-plain-secret-1234"));

        verify(knowledgeBaseMapper).insert(captor.capture());
        assertNotEquals("sk-plain-secret-1234", captor.getValue().getApiKey());
        assertTrue(captor.getValue().getApiKey().length() > 0);
    }

    @Test
    void create_shouldRejectMissingApiKey() {
        assertThrows(BizException.class, () -> service.create(request("产品知识库", null)));
    }

    @Test
    void create_shouldApplyDefaults_whenOptionalFieldsBlank() {
        ArgumentCaptor<AiKnowledgeBase> captor = ArgumentCaptor.forClass(AiKnowledgeBase.class);

        service.create(request("产品知识库", "sk-1"));

        verify(knowledgeBaseMapper).insert(captor.capture());
        AiKnowledgeBase saved = captor.getValue();
        assertEquals("application/json", saved.getContentType());
        assertEquals("", saved.getExtraHeaders());
        assertEquals(KnowledgeBaseEndpoint.DEFAULT_TOP_N, saved.getTopN());
        // 阈值默认 0：外部 rerank 分数量级只有 0.1x，按 0.5 之类拍脑袋会把召回全丢光
        assertEquals(0, saved.getScoreThreshold().compareTo(BigDecimal.ZERO));
    }

    @Test
    void update_shouldKeepOldApiKey_whenRequestApiKeyBlank() {
        AiKnowledgeBase kb = existing(1L, "sk-original-secret");
        String originalCipher = kb.getApiKey();
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        service.update(1L, request("产品知识库", ""));

        ArgumentCaptor<AiKnowledgeBase> captor = ArgumentCaptor.forClass(AiKnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(captor.capture());
        assertEquals(originalCipher, captor.getValue().getApiKey());
    }

    @Test
    void get_shouldReturnMaskedApiKey_notCiphertextOrPlainText() {
        AiKnowledgeBase kb = existing(1L, "sk-abcd1234wxyz");
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        KnowledgeBaseVO vo = service.get(1L);

        assertNotEquals("sk-abcd1234wxyz", vo.getApiKeyMasked());
        assertNotEquals(kb.getApiKey(), vo.getApiKeyMasked());
        assertTrue(vo.getApiKeyMasked().endsWith("wxyz"));
    }

    // ---- 保存门禁 ----

    @Test
    void create_shouldBlockSave_whenConnectivityProbeFails() {
        when(searchClient.searchOne(any(KnowledgeBaseEndpoint.class), anyString()))
            .thenThrow(new BizException(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED, "connection refused"));

        assertThrows(BizException.class, () -> service.create(request("产品知识库", "sk-1")));
        verify(knowledgeBaseMapper, never()).insert(any(AiKnowledgeBase.class));
    }

    @Test
    void create_shouldPersistTestSuccess_whenGatePassed() {
        ArgumentCaptor<AiKnowledgeBase> captor = ArgumentCaptor.forClass(AiKnowledgeBase.class);

        service.create(request("产品知识库", "sk-1"));

        verify(knowledgeBaseMapper).insert(captor.capture());
        assertEquals(ConnectivityTestStatus.SUCCESS, captor.getValue().getTestStatus());
    }

    @Test
    void update_shouldSkipProbe_whenOnlyNameOrRemarkChanged() {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));

        // 连接参数（baseUrl/appId/apiKey/contentType/extraHeaders）全部未变，只改名
        service.update(1L, new KnowledgeBaseSaveRequest("新名字", "http://localhost:20002", "app_123", "",
            "application/json", "", 5, BigDecimal.ZERO, 1, "新备注"));

        verify(searchClient, never()).searchOne(any(KnowledgeBaseEndpoint.class), anyString());
    }

    @Test
    void update_shouldProbe_whenBaseUrlChanged() {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));

        service.update(1L, new KnowledgeBaseSaveRequest("产品知识库", "http://localhost:30003", "app_123", "",
            "application/json", "", 5, BigDecimal.ZERO, 1, null));

        verify(searchClient).searchOne(any(KnowledgeBaseEndpoint.class), anyString());
    }

    @Test
    void update_shouldBlockSave_whenChangedConnectionFailsProbe() {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));
        when(searchClient.searchOne(any(KnowledgeBaseEndpoint.class), anyString()))
            .thenThrow(new BizException(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED, "unauthorized"));

        assertThrows(BizException.class, () -> service.update(1L,
            new KnowledgeBaseSaveRequest("产品知识库", "http://localhost:20002", "app_999", "",
                "application/json", "", 5, BigDecimal.ZERO, 1, null)));
        verify(knowledgeBaseMapper, never()).updateById(any(AiKnowledgeBase.class));
    }

    // ---- 校验 / 删除 / 状态 ----

    @Test
    void create_shouldRejectDuplicateName() {
        when(knowledgeBaseMapper.exists(any())).thenReturn(true);

        assertThrows(BizException.class, () -> service.create(request("产品知识库", "sk-1")));
    }

    @Test
    void create_shouldRejectMalformedExtraHeaders() {
        KnowledgeBaseSaveRequest request = new KnowledgeBaseSaveRequest("产品知识库", "http://localhost:20002",
            "app_123", "sk-1", null, "{not-json", null, null, 1, null);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void delete_shouldRejectUnknownId() {
        when(knowledgeBaseMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.delete(999L));
    }

    @Test
    void delete_shouldRejectWhenReferencedByAgent() {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));
        when(agentKnowledgeBaseMapper.exists(any())).thenReturn(true);

        assertThrows(BizException.class, () -> service.delete(1L));
        verify(knowledgeBaseMapper, never()).deleteById(1L);
    }

    @Test
    void delete_shouldSucceed_whenNotReferenced() {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));
        when(agentKnowledgeBaseMapper.exists(any())).thenReturn(false);

        service.delete(1L);

        verify(knowledgeBaseMapper).deleteById(1L);
    }

    @Test
    void updateStatus_shouldOnlyUpdateStatusColumn() {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));
        ArgumentCaptor<AiKnowledgeBase> captor = ArgumentCaptor.forClass(AiKnowledgeBase.class);

        service.updateStatus(1L, 0);

        verify(knowledgeBaseMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
        assertEquals(null, captor.getValue().getKbName(), "启停不应连带改动其它字段");
    }

    // ---- 连通性测试 / 下拉选项 ----

    @Test
    void testConnectivity_shouldPersistResultAndReturnHitCount() throws Exception {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));

        KnowledgeBaseTestResult result = service.testConnectivity(1L).get();

        assertEquals(ConnectivityTestStatus.SUCCESS, result.testStatus());
        assertEquals(1, result.hitCount());
        verify(knowledgeBaseMapper).updateById(any(AiKnowledgeBase.class));
    }

    @Test
    void testConnectivity_shouldPersistFailure_whenProbeThrows() throws Exception {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));
        when(searchClient.searchOne(any(KnowledgeBaseEndpoint.class), anyString()))
            .thenThrow(new BizException(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED, "timeout"));

        KnowledgeBaseTestResult result = service.testConnectivity(1L).get();

        assertEquals(ConnectivityTestStatus.FAILED, result.testStatus());
        verify(knowledgeBaseMapper).updateById(any(AiKnowledgeBase.class));
    }

    @Test
    void options_shouldOnlyReturnEnabledAndTestedKnowledgeBases() {
        // 过滤条件下沉到 SQL（status=1 且 test_status=1），这里断言映射结果
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(existing(1L, "sk-1")));

        List<KnowledgeBaseOptionVO> options = service.options();

        assertEquals(1, options.size());
        assertEquals(1L, options.get(0).id());
        assertEquals("产品知识库", options.get(0).kbName());
    }

    // ---- 软删除 + 不含 deleted 列的唯一索引 ----

    /**
     * 删除（逻辑删除）后用同名重建：旧行仍占着 uk_ai_kb_name，直接 insert 必撞唯一约束报 500。
     * 修法是复活旧行再整体覆盖字段——名字必须能被重新使用，否则删过一次就永久占用。
     */
    @Test
    void create_shouldReviveSoftDeletedRow_whenSameNameRecreated() {
        AiKnowledgeBase softDeleted = existing(7L, "sk-old");
        when(knowledgeBaseMapper.selectDeletedByName("产品知识库")).thenReturn(softDeleted);

        service.create(request("产品知识库", "sk-new"));

        verify(knowledgeBaseMapper).reviveDeleted(7L);
        verify(knowledgeBaseMapper, never()).insert(any(AiKnowledgeBase.class));
        ArgumentCaptor<AiKnowledgeBase> captor = ArgumentCaptor.forClass(AiKnowledgeBase.class);
        verify(knowledgeBaseMapper).updateById(captor.capture());
        // 复活的是旧行 id，但业务字段整体按本次请求覆盖（含新 AppKey）
        assertEquals(7L, captor.getValue().getId());
        assertEquals("sk-new", new AesGcmCryptoUtil(TEST_SECRET_KEY).decrypt(captor.getValue().getApiKey()));
    }

    /** 无同名软删行时走正常 insert，不该有多余的复活调用。 */
    @Test
    void create_shouldInsertNormally_whenNoSoftDeletedRowHoldsTheName() {
        when(knowledgeBaseMapper.selectDeletedByName(anyString())).thenReturn(null);

        service.create(request("新知识库", "sk-1"));

        verify(knowledgeBaseMapper).insert(any(AiKnowledgeBase.class));
        verify(knowledgeBaseMapper, never()).reviveDeleted(any());
    }

    /** 并发竞争撞唯一键：必须兜成友好的业务异常，而不是让 DuplicateKeyException 变成 SYSTEM_ERROR 500。 */
    @Test
    void create_shouldFailFriendly_whenInsertRacesOnUniqueKey() {
        when(knowledgeBaseMapper.selectDeletedByName(anyString())).thenReturn(null);
        when(knowledgeBaseMapper.insert(any(AiKnowledgeBase.class)))
            .thenThrow(new DuplicateKeyException("Duplicate entry '产品知识库' for key 'uk_ai_kb_name'"));

        BizException ex = assertThrows(BizException.class, () -> service.create(request("产品知识库", "sk-1")));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, ex.getResultCode());
        assertTrue(ex.getMessage().contains("产品知识库"));
    }

    /** 改名撞上软删旧行：改名不能走复活（会变成两行同名），只能给友好提示让用户换名。 */
    @Test
    void update_shouldFailFriendly_whenRenameHitsSoftDeletedName() {
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(existing(1L, "sk-1"));
        when(knowledgeBaseMapper.updateById(any(AiKnowledgeBase.class)))
            .thenThrow(new DuplicateKeyException("Duplicate entry '归档库' for key 'uk_ai_kb_name'"));

        BizException ex = assertThrows(BizException.class, () -> service.update(1L, request("归档库", null)));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, ex.getResultCode());
        assertTrue(ex.getMessage().contains("归档库"));
    }
}
