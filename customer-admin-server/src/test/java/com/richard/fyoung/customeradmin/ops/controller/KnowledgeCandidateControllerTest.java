package com.richard.fyoung.customeradmin.ops.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateService;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest;
import com.richard.fyoung.customeradmin.improvement.controller.ImprovementCaseController;
import com.richard.fyoung.customeradmin.improvement.service.ImprovementCaseService;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateBindRequest;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidatePublishRequest;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 HTTP 校验与 Sa-Token 权限拦截，确认只读入口、组合写权限及实际操作人。 */
class KnowledgeCandidateControllerTest {
    private static final String CANDIDATE_ID = "478b7f10-46ba-4217-947f-d86528aa8fbe";
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final String PATH = "/api/ops/knowledge-gap/candidates/";
    private static final String VALID = """
        {"questionHash":"%s","expectedRevision":0,"sourceReviewRevision":2,
         "title":"开票规则","content":"从已完成订单申请电子发票","keyword":"电子发票"}
        """.formatted(SOURCE_HASH);
    private final KnowledgeCandidateService service = mock(KnowledgeCandidateService.class);
    private final ImprovementCaseService improvements = mock(ImprovementCaseService.class);
    private static final String BIND_PATH = "/api/improvement-cases/1/knowledge-candidate";
    private static final String BIND_BODY = """
        {"candidateId":"%s","candidateRevision":2,"agentId":7,"modelDeploymentId":11,
         "judgeDeploymentId":12,"datasetReleaseId":"release-1","targetCaseId":"target"}
        """.formatted(CANDIDATE_ID);
    private SaTokenConfig previousConfig;
    private SaTokenDao previousDao;
    private SaTokenContext previousContext;
    private StpInterface previousPermissions;
    private StpLogic previousLogic;
    private SaTokenDaoDefaultImpl testDao;
    private MockMvc mvc;
    private String token;
    private List<String> permissions;

    @BeforeEach
    void setUp() {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        previousPermissions = SaManager.getStpInterface();
        previousLogic = StpUtil.getStpLogic();
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setIsReadCookie(false));
        testDao = new SaTokenDaoDefaultImpl();
        SaManager.setSaTokenDao(testDao);
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        permissions = List.of("knowledge-gap:view", "knowledge-gap:fill");
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return permissions;
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        });
        StpUtil.setStpLogic(new StpLogic(previousLogic.getLoginType()));
        token = StpUtil.getStpLogic().createLoginSession("42");
        TenantContext.set("tenant-a");
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeCandidateController(service), new ImprovementCaseController(improvements))
            .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new SaInterceptor(handler -> StpUtil.checkLogin())).build();
    }

    @AfterEach
    void restoreGlobalState() {
        TenantContext.clear();
        StpUtil.setStpLogic(previousLogic);
        SaManager.setStpInterface(previousPermissions);
        SaManager.setSaTokenContext(previousContext);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setConfig(previousConfig);
        testDao.destroy();
    }

    @Test
    void anonymousRequestsCannotReadOrSave() throws Exception {
        mvc.perform(get(PATH + "source/" + SOURCE_HASH)).andExpect(jsonPath("$.code").value(10001));
        mvc.perform(put(PATH + CANDIDATE_ID).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge-gap:view", "knowledge-gap:fill", "improvement:manage"})
    void savingRequiresBothViewAndFill(String permission) throws Exception {
        permissions = List.of(permission);
        mvc.perform(put(PATH + CANDIDATE_ID).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(service);
    }

    @Test
    void readOnlyUserCanRestoreCandidate() throws Exception {
        permissions = List.of("knowledge-gap:view");
        mvc.perform(get(PATH + "source/" + SOURCE_HASH).header("Authorization", token))
            .andExpect(jsonPath("$.code").value(0));
        verify(service).bySource(SOURCE_HASH);
    }

    @Test
    void writePermissionAloneCannotRead() throws Exception {
        permissions = List.of("knowledge-gap:fill");
        mvc.perform(get(PATH + "source/" + SOURCE_HASH).header("Authorization", token))
            .andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(service);
    }

    @Test
    void validSaveUsesAuthenticatedActorAndIgnoresCallerIdentityParameters() throws Exception {
        mvc.perform(put(PATH + CANDIDATE_ID).header("Authorization", token)
            .param("tenantId", "other").param("actor", "999").param("status", "PUBLISHED")
            .contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(jsonPath("$.code").value(0));
        verify(service).save(eq(CANDIDATE_ID), any(KnowledgeCandidateSaveRequest.class), eq(42L));
    }

    @Test
    void draftMustRejectContentThatCannotFitThePublishedFaqColumns() throws Exception {
        for (String body : List.of(
            VALID.replace("从已完成订单申请电子发票", "内".repeat(21846)),
            VALID.replace("电子发票\"}", "词".repeat(256) + "\"}"))) {
            mvc.perform(put(PATH + CANDIDATE_ID).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(30001));
        }
        verifyNoInteractions(service);
    }

    @Test
    void acceptsChineseAndEmojiAtTheDocumentedCapacityBoundary() throws Exception {
        for (String content : List.of("内".repeat(20000), "😀".repeat(10000))) {
            String body = VALID.replace("从已完成订单申请电子发票", content)
                .replace("电子发票\"}", "词".repeat(255) + "\"}");
            mvc.perform(put(PATH + CANDIDATE_ID).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
            verify(service).save(eq(CANDIDATE_ID), eq(new KnowledgeCandidateSaveRequest(
                SOURCE_HASH, 0, 2, "开票规则", content, "词".repeat(255))), eq(42L));
        }
    }

    @Test
    void invalidDraftIsRejectedBeforeServiceInvocation() throws Exception {
        for (String body : new String[]{
            VALID.replace(SOURCE_HASH, "invalid"), VALID.replace("开票规则", "   "),
            VALID.replace("开票规则", "标".repeat(201)),
            VALID.replace("从已完成订单申请电子发票", "内".repeat(20001)),
            VALID.replace("电子发票\"}", "词".repeat(256) + "\"}"),
            VALID.replace("\"expectedRevision\":0", "\"expectedRevision\":-1"),
            VALID.replace("\"sourceReviewRevision\":2", "\"sourceReviewRevision\":0")}) {
            mvc.perform(put(PATH + CANDIDATE_ID).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(30001));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge-gap:view", "knowledge-gap:fill", "improvement:manage"})
    void bindingRequiresEveryPermission(String missing) throws Exception {
        permissions = List.of("knowledge-gap:view", "knowledge-gap:fill", "improvement:manage").stream()
            .filter(permission -> !permission.equals(missing)).toList();
        mvc.perform(post(BIND_PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(BIND_BODY))
            .andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(improvements);
    }

    @Test
    void bindingUsesAuthenticatedActorAndOnlyAcceptsInputSelections() throws Exception {
        permissions = List.of("knowledge-gap:view", "knowledge-gap:fill", "improvement:manage");
        mvc.perform(post(BIND_PATH).header("Authorization", token).param("tenantId", "other").param("actor", "999")
            .contentType(MediaType.APPLICATION_JSON).content(BIND_BODY)).andExpect(jsonPath("$.code").value(0));
        verify(improvements).bindKnowledgeCandidate(1L,
            new KnowledgeCandidateBindRequest(CANDIDATE_ID, 2, 7L, 11L, 12L, "release-1", "target"), 42L);
    }

    @Test
    void invalidBindingSelectionsNeverReachTheService() throws Exception {
        permissions = List.of("knowledge-gap:view", "knowledge-gap:fill", "improvement:manage");
        for (String body : List.of(BIND_BODY.replace(CANDIDATE_ID, "invalid"),
            BIND_BODY.replace("\"candidateRevision\":2", "\"candidateRevision\":0"),
            BIND_BODY.replace("\"modelDeploymentId\":11", "\"modelDeploymentId\":null"),
            BIND_BODY.replace("\"judgeDeploymentId\":12", "\"judgeDeploymentId\":-1"),
            BIND_BODY.replace("\"targetCaseId\":\"target\"", "\"targetCaseId\":\" \""))) {
            mvc.perform(post(BIND_PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(30001));
        }
        verifyNoInteractions(improvements);
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge-gap:view", "improvement:manage", "eval:run"})
    void knowledgeEvaluationRequiresKnowledgeAccessAndRunPermission(String missing) throws Exception {
        permissions = List.of("knowledge-gap:view", "improvement:manage", "eval:run").stream()
            .filter(permission -> !permission.equals(missing)).toList();
        mvc.perform(post(BIND_PATH + "/reevaluate").header("Authorization", token))
            .andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(improvements);
    }

    @Test
    void authorizedKnowledgeEvaluationUsesTheKnowledgeEntry() throws Exception {
        permissions = List.of("knowledge-gap:view", "improvement:manage", "eval:run");
        mvc.perform(post(BIND_PATH + "/reevaluate").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"复评\"}"))
            .andExpect(jsonPath("$.code").value(0));
        verify(improvements).reevaluateKnowledgeCandidate(1L, "复评");
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge-gap:view", "improvement:manage", "eval:view"})
    void readingPairedAnswersRequiresKnowledgeAndDatasetReadPermissions(String missing) throws Exception {
        permissions = List.of("knowledge-gap:view", "improvement:manage", "eval:view").stream()
            .filter(permission -> !permission.equals(missing)).toList();
        mvc.perform(get(BIND_PATH).header("Authorization", token)).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(improvements);
    }

    @Test
    void authorizedReaderCanInspectPairedAnswersWithoutWriteOrRunPermissions() throws Exception {
        permissions = List.of("knowledge-gap:view", "improvement:manage", "eval:view");
        mvc.perform(get(BIND_PATH).header("Authorization", token)).andExpect(jsonPath("$.code").value(0));
        verify(improvements).knowledgeCandidateReview(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge-gap:view", "knowledge-gap:fill", "improvement:manage", "eval:view"})
    void publicationRequiresEveryKnowledgeAndEvidencePermission(String missing) throws Exception {
        permissions = List.of("knowledge-gap:view", "knowledge-gap:fill", "improvement:manage", "eval:view").stream()
            .filter(permission -> !permission.equals(missing)).toList();
        mvc.perform(post(BIND_PATH + "/publish").header("Authorization", token)).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(improvements);
    }

    @Test
    void publicationActorComesFromAuthenticatedSessionAndBodyCannotSupplyAnotherArtifact() throws Exception {
        permissions = List.of("knowledge-gap:view", "knowledge-gap:fill", "improvement:manage", "eval:view");
        mvc.perform(post(BIND_PATH + "/publish").header("Authorization", token)
            .param("actor", "999").contentType(MediaType.APPLICATION_JSON)
            .content("{\"tenantId\":\"other\",\"content\":\"未经评测正文\",\"status\":\"PASSED\","
                + "\"expectedArtifactFingerprint\":\"" + "c".repeat(64) + "\",\"expectedEvaluationRunId\":\"reviewed-run\"}"))
            .andExpect(jsonPath("$.code").value(0));
        verify(improvements).publishKnowledgeCandidate(1L, new KnowledgeCandidatePublishRequest("c".repeat(64), "reviewed-run"), 42L);
    }

    @Test
    void publicationRequiresAWellFormedReviewedVersionAndRun() throws Exception {
        permissions = List.of("knowledge-gap:view", "knowledge-gap:fill", "improvement:manage", "eval:view");
        for (String body : List.of("{}", "{\"expectedArtifactFingerprint\":\"invalid\",\"expectedEvaluationRunId\":\"run\"}",
            "{\"expectedArtifactFingerprint\":\"" + "c".repeat(64) + "\",\"expectedEvaluationRunId\":\"\"}")) {
            mvc.perform(post(BIND_PATH + "/publish").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(30001));
        }
        verifyNoInteractions(improvements);
    }
}
