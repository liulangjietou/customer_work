package com.richard.fyoung.customeradmin.workspace.chat.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
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
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatReceipt;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatHistoryService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatAttachmentService;
import com.richard.fyoung.customeradmin.workspace.chat.service.WorkspaceMessageAcceptanceService;
import com.richard.fyoung.customeradmin.workspace.callstats.service.AgentCallMetaFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.controller.VibeCodingController;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.VibeCodingService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.GitAssistantService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.CollaborativeCodingService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.AiCodingTaskService;
import com.richard.fyoung.customeradmin.workspace.runtime.SandboxCommandService;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 两个回执 HTTP 入口都执行真实 Sa-Token 权限拦截，资源身份只能取已认证调用者。 */
class WorkspaceMessageReceiptControllerTest {
    private static final String CLIENT_ID = "478b7f10-46ba-4217-947f-d86528aa8fbe";
    private final WorkspaceSessionGuard sessionGuard = mock(WorkspaceSessionGuard.class);
    private final WorkspaceMessageAcceptanceService service = mock(WorkspaceMessageAcceptanceService.class);
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
        var chat = new ChatController(mock(ChatService.class), mock(ChatHistoryService.class), mock(ChatAttachmentService.class),
            mock(AgentCallMetaFactory.class), sessionGuard);
        chat.setAcceptanceService(service);
        var vibe = new VibeCodingController(mock(VibeCodingService.class), mock(GitAssistantService.class),
            mock(CollaborativeCodingService.class), sessionGuard, mock(SandboxCommandService.class),
            mock(AiCodingTaskService.class), new com.fasterxml.jackson.databind.ObjectMapper());
        vibe.setAcceptanceService(service);
        mvc = MockMvcBuilders.standaloneSetup(chat, vibe)
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

    @ParameterizedTest
    @ValueSource(strings = {"chat", "vibecoding"})
    void anonymousRequestCannotReadReceipt(String channel) throws Exception {
        mvc.perform(get(path(channel))).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(service, sessionGuard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "vibecoding"})
    void workspacePermissionIsRequired(String channel) throws Exception {
        permissions = List.of("user-order:view");
        mvc.perform(get(path(channel)).header("Authorization", token)).andExpect(jsonPath("$.code").value(20001));
        verifyNoInteractions(service, sessionGuard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "vibecoding"})
    void receiptOwnerComesFromLoginInsteadOfRequestParameters(String channel) throws Exception {
        when(service.receipt("refund", "session-1", 42, channel, CLIENT_ID)).thenReturn(new ChatReceipt(CLIENT_ID, 1, null));
        mvc.perform(get(path(channel)).header("Authorization", token).param("ownerId", "999").param("tenantId", "other"))
            .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.clientMessageId").value(CLIENT_ID));
        verify(sessionGuard).requireOwned("refund", "session-1", 42L);
        verify(service).receipt("refund", "session-1", 42, channel, CLIENT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "vibecoding"})
    void foreignSessionIsRejectedBeforeLookingUpReceipt(String channel) throws Exception {
        doThrow(new BizException(ResultCode.RESOURCE_NOT_FOUND)).when(sessionGuard).requireOwned("refund", "session-1", 42L);
        mvc.perform(get(path(channel)).header("Authorization", token)).andExpect(jsonPath("$.code").value(30003));
        verifyNoInteractions(service);
    }

    private String path(String channel) {
        return "/api/workspace/refund/" + channel + "/sessions/session-1/receipts/" + CLIENT_ID;
    }
}
