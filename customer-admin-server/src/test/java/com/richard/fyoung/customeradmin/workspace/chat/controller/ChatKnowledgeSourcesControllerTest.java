package com.richard.fyoung.customeradmin.workspace.chat.controller;

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
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto.KnowledgeDocumentPreviewVO;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatKnowledgeSourcesVO;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatKnowledgeSourcesService;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 真实 Sa-Token 登录和权限拦截；数据服务独立隔离，身份不可由查询参数冒充。 */
class ChatKnowledgeSourcesControllerTest {
    private static final String PATH = "/api/workspace/refund/chat/sessions/s1/messages/reply-1/sources";
    private final WorkspaceSessionGuard sessionGuard = mock(WorkspaceSessionGuard.class);
    private final ChatKnowledgeSourcesService service = mock(ChatKnowledgeSourcesService.class);
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
        permissions = List.of("workspace");
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
        mvc = MockMvcBuilders.standaloneSetup(new ChatKnowledgeSourcesController(service, sessionGuard))
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
    void messageSourcesShouldHaveAWorkspaceReadEndpoint() throws Exception {
        when(service.sources(eq("refund"), eq("s1"), eq("reply-1"), any()))
            .thenReturn(new ChatKnowledgeSourcesVO(ChatKnowledgeSourcesVO.Status.NOT_RECORDED, List.of()));
        mvc.perform(get(PATH).header("Authorization", token))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("NOT_RECORDED"));
        verify(sessionGuard).requireOwned("refund", "s1", 42L);
    }

    @Test
    void anonymousAndMissingWorkspacePermissionCannotReachEitherRead() throws Exception {
        mvc.perform(get(PATH)).andExpect(jsonPath("$.code").value(10001));
        mvc.perform(get(PATH + "/0/preview")).andExpect(jsonPath("$.code").value(10001));
        permissions = List.of("knowledge-base:view", "knowledge-base:source-preview");
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(jsonPath("$.code").value(20001));
        mvc.perform(get(PATH + "/0/preview").header("Authorization", token))
            .andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(service, sessionGuard);
    }

    @Test
    void bothReadPathsRequireCurrentSessionOwnership() throws Exception {
        org.mockito.Mockito.doThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND))
            .when(sessionGuard).requireOwned("refund", "s1", 42L);
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(jsonPath("$.code").value(30003));
        mvc.perform(get(PATH + "/0/preview").header("Authorization", token))
            .andExpect(jsonPath("$.code").value(30003));
        verifyNoInteractions(service);
    }

    @Test
    void previewUsesLoginIdentityAndCannotBeCached() throws Exception {
        when(service.preview(eq("refund"), eq("s1"), eq("reply-1"), eq(0), any()))
            .thenReturn(new KnowledgeDocumentPreviewVO(null, "可信原文"));
        mvc.perform(get(PATH + "/0/preview").header("Authorization", token)
                .param("tenantId", "tenant-b").param("subjectId", "999")
                .param("knowledgeBaseId", "999").param("revisionId", "999"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.content").value("可信原文"));
        var identity = ArgumentCaptor.forClass(AgentInvocationIdentity.class);
        verify(service).preview(eq("refund"), eq("s1"), eq("reply-1"), eq(0), identity.capture());
        verify(sessionGuard).requireOwned("refund", "s1", 42L);
        assertEquals("tenant-a", identity.getValue().tenantId());
        assertEquals("42", identity.getValue().subjectId());
        assertEquals(QuotaSubjectType.ADMIN_USER, identity.getValue().subjectType());
        assertEquals(AgentInvocationIdentity.CHANNEL_ADMIN, identity.getValue().channelCode());
    }

    @Test
    void readFailureIsNotReportedAsAnEmptyRetrieval() throws Exception {
        when(service.sources(eq("refund"), eq("s1"), eq("reply-1"), any()))
            .thenThrow(new IllegalStateException("private database address"));
        var result = mvc.perform(get(PATH).header("Authorization", token)).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(result.getResponse().getContentAsString().contains("private database address"));
        org.junit.jupiter.api.Assertions.assertFalse(result.getResponse().getContentAsString().contains("NOT_RECORDED"));
        org.junit.jupiter.api.Assertions.assertFalse(result.getResponse().getContentAsString().contains("RECORDED"));
    }
}
