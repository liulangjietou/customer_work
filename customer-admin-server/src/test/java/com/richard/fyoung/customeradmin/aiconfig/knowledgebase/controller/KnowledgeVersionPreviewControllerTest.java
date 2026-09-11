package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.controller;

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
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeVersionPreviewService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 Sa-Token 登录和权限拦截；数据服务独立隔离，身份不可由查询参数冒充。 */
class KnowledgeVersionPreviewControllerTest {
    private static final String PATH = "/api/aiconfig/knowledge-base/7/versions/70/documents/100/preview";
    private final KnowledgeVersionPreviewService service = mock(KnowledgeVersionPreviewService.class);
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
        permissions = List.of("knowledge-base:view", "knowledge-base:source-preview");
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
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeVersionPreviewController(service))
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
    void anonymousRequestNeverReachesTheSource() throws Exception {
        mvc.perform(get(PATH)).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge-base:view", "knowledge-base:source-preview"})
    void eitherPermissionAloneIsInsufficient(String singlePermission) throws Exception {
        permissions = List.of(singlePermission);
        mvc.perform(get(PATH).header("Authorization", token))
            .andExpect(jsonPath("$.code").value(20001));
        mvc.perform(get("/api/aiconfig/knowledge-base/7/versions/70/documents")
                .header("Authorization", token))
            .andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(service);
    }

    @Test
    void previewUsesLoginIdentityAndCannotBeCached() throws Exception {
        when(service.preview(eq(7L), eq(70L), eq(100L), any()))
            .thenReturn(new KnowledgeDocumentPreviewVO(null, "可信原文"));
        mvc.perform(get(PATH).header("Authorization", token)
                .param("tenantId", "tenant-b").param("subjectId", "999"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.content").value("可信原文"));
        var identity = ArgumentCaptor.forClass(AgentInvocationIdentity.class);
        verify(service).preview(eq(7L), eq(70L), eq(100L), identity.capture());
        assertEquals("tenant-a", identity.getValue().tenantId());
        assertEquals("42", identity.getValue().subjectId());
        assertEquals(QuotaSubjectType.ADMIN_USER, identity.getValue().subjectType());
        assertEquals(AgentInvocationIdentity.CHANNEL_ADMIN, identity.getValue().channelCode());
    }

    @Test
    void expiredSourceAndInvalidPageAreExplicitFailuresWithoutBody() throws Exception {
        when(service.preview(eq(7L), eq(70L), eq(100L), any()))
            .thenThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND));
        mvc.perform(get(PATH).header("Authorization", token))
            .andExpect(jsonPath("$.code").value(30003))
            .andExpect(jsonPath("$.data.content").doesNotExist());
        mvc.perform(get("/api/aiconfig/knowledge-base/7/versions/70/documents")
                .param("pageNum", "0").header("Authorization", token))
            .andExpect(jsonPath("$.code").value(30001));
    }
}
