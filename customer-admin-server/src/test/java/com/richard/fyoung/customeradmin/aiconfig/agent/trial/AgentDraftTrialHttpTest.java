package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.service.AgentDraftService;
import com.richard.fyoung.customeradmin.aiconfig.agent.publication.AgentPublicationCheckController;
import com.richard.fyoung.customeradmin.aiconfig.agent.publication.AgentPublicationCheckService;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.workspace.security.AdminAgentIdentityInterceptor;
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

/** 真实登录、权限拦截及可信身份注入；不依赖浏览器对按钮的隐藏保护接口。 */
class AgentDraftTrialHttpTest {
    private static final String DRAFT_ID = "df318d12-49a8-423c-b47f-2d5bc66c3d71";
    private static final String TRIAL_ID = "ab318d12-49a8-423c-b47f-2d5bc66c3d72";
    private static final String PATH = "/api/aiconfig/agent-drafts/" + DRAFT_ID + "/trials";
    private static final String PUBLICATION = "/api/aiconfig/agent/7/publication-check";
    private final AgentDraftService drafts = mock(AgentDraftService.class);
    private final AgentDraftTrialService trials = mock(AgentDraftTrialService.class);
    private final AgentPublicationCheckService publication = mock(AgentPublicationCheckService.class);
    private final AgentDraftVO draft = new AgentDraftVO(DRAFT_ID, null, null, "个人草稿", 3L, 1L, null);
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
        permissions = List.of("agent:view", "agent:add", "eval:view");
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
        TenantContext.set(TenantContext.DEFAULT);
        mvc = MockMvcBuilders.standaloneSetup(new AgentDraftTrialController(drafts, trials), new AgentPublicationCheckController(publication))
            .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new SaInterceptor(handler -> StpUtil.checkLogin()))
            .addMappedInterceptors(new String[]{"/api/aiconfig/agent-drafts/*/trials/**"}, new AdminAgentIdentityInterceptor()).build();
        when(drafts.get(DRAFT_ID, 42L)).thenReturn(draft);
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
    void anonymousRequestsCannotReachDraftsOrPublicationFacts() throws Exception {
        mvc.perform(get(PATH + "/preview")).andExpect(jsonPath("$.code").value(10001));
        mvc.perform(get(PUBLICATION)).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(drafts, trials, publication);
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent:view", "eval:view"})
    void publicationRequiresBothReadPermissions(String only) throws Exception {
        permissions = List.of(only);
        mvc.perform(get(PUBLICATION).header("Authorization", token)).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(publication);
    }

    @Test
    void publicationFactsCannotBeCachedAcrossLogins() throws Exception {
        mvc.perform(get(PUBLICATION).header("Authorization", token))
            .andExpect(jsonPath("$.code").value(0)).andExpect(header().string("Cache-Control", "no-store"));
        verify(publication).check(7L);
    }

    @Test
    void personalPreviewAndReceiptsCannotBeCached() throws Exception {
        for (String suffix : List.of("/preview", "", "/" + TRIAL_ID)) {
            mvc.perform(get(PATH + suffix).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0)).andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void previewRequiresTheWritePermissionOfItsImmutableDraftOrigin() throws Exception {
        permissions = List.of("agent:view");
        mvc.perform(get(PATH + "/preview").header("Authorization", token)).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(trials);
        permissions = List.of("agent:view", "agent:add");
        when(drafts.get(DRAFT_ID, 42L)).thenReturn(new AgentDraftVO(DRAFT_ID, 7L, 1L, "已有配置", 3L, 1L, null));
        mvc.perform(get(PATH + "/preview").header("Authorization", token)).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(trials);
    }

    @Test
    void requestCannotReplaceTheTrustedTenantOwnerOrTrialIdentity() throws Exception {
        mvc.perform(put(PATH + "/" + TRIAL_ID).header("Authorization", token)
                .param("tenantId", "other").param("ownerId", "999")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedDraftVersion\":3,\"input\":\"如何退款\"}"))
            .andExpect(jsonPath("$.code").value(0));
        var identity = ArgumentCaptor.forClass(AgentInvocationIdentity.class);
        verify(trials).start(eq(draft), eq(42L), eq(TRIAL_ID), any(), identity.capture());
        assertEquals("42", identity.getValue().subjectId());
        assertEquals(TenantContext.DEFAULT, identity.getValue().tenantId());
        assertEquals(QuotaSubjectType.ADMIN_USER, identity.getValue().subjectType());
        assertNull(AgentInvocationIdentity.capture(), "请求线程的身份必须在完成后清理");
    }

    @Test
    void invalidInputStopsBeforeAnyDraftReadOrTrialAcceptance() throws Exception {
        mvc.perform(put(PATH + "/" + TRIAL_ID).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedDraftVersion\":0,\"input\":\"\"}"))
            .andExpect(jsonPath("$.code").value(30001));
        verifyNoInteractions(drafts, trials);
    }
}
