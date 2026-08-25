package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallRequest;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.runtime.AdminMcpFactory;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpService} 单测：mcpType 校验（含新增的 http）、连通性测试装配、脱敏无关字段回显。
 * @author owlzhangfq@gmail.com
 */
class McpServiceTest {

    private AiMcpMapper mcpMapper;
    private AiAgentMcpMapper agentMcpMapper;
    private AdminMcpFactory mcpFactory;
    private McpService service;

    @BeforeEach
    void setUp() {
        mcpMapper = mock(AiMcpMapper.class);
        agentMcpMapper = mock(AiAgentMcpMapper.class);
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        AgentInstanceCache agentInstanceCache = mock(AgentInstanceCache.class);
        mcpFactory = mock(AdminMcpFactory.class);
        service = new McpService(mcpMapper, agentMcpMapper, agentMapper, agentInstanceCache, mcpFactory);
    }

    @Test
    void create_shouldAcceptHttpType() {
        McpSaveRequest request = new McpSaveRequest("测试MCP", "http", "{\"url\": \"https://mcp.example.com/mcp\"}", null, 1);

        service.create(request);

        ArgumentCaptor<AiMcp> captor = ArgumentCaptor.forClass(AiMcp.class);
        verify(mcpMapper).insert(captor.capture());
        assertEquals("ADMIN_USER", captor.getValue().getAllowedSubjectTypes());
    }

    @Test
    void create_shouldPersistNormalizedSubjectPolicy() {
        McpSaveRequest request = new McpSaveRequest("测试MCP", "http",
            "{\"url\": \"https://mcp.example.com/mcp\"}", null,
            List.of("api_key", "user"), 1);

        service.create(request);

        ArgumentCaptor<AiMcp> captor = ArgumentCaptor.forClass(AiMcp.class);
        verify(mcpMapper).insert(captor.capture());
        assertEquals("USER,API_KEY", captor.getValue().getAllowedSubjectTypes());
    }

    @Test
    void create_shouldRejectEmptyOrUnknownSubjectPolicy() {
        assertThrows(BizException.class, () -> service.create(new McpSaveRequest("测试MCP", "http",
            "{\"url\": \"https://mcp.example.com/mcp\"}", null, List.of("unknown"), 1)));
    }

    @Test
    void create_shouldRejectUnknownType() {
        McpSaveRequest request = new McpSaveRequest("测试MCP", "websocket", "{}", null, 1);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void create_shouldRejectInvalidConfigJson() {
        McpSaveRequest request = new McpSaveRequest("测试MCP", "sse", "not-json", null, 1);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void create_shouldRejectNonObjectJsonRoots() {
        List<String> invalidRoots = List.of("null", "[]", "\"secret scalar\"", "1", "true");

        for (String root : invalidRoots) {
            BizException error = assertThrows(BizException.class,
                () -> service.create(new McpSaveRequest("测试MCP", "http", root, null, 1)));
            assertTrue(error.getMessage().contains("JSON object"), root);
        }
    }

    @Test
    void create_shouldRejectRemoteUrlCredentialCarriersAndNonHttpSchemes() {
        List<String> unsafeUrls = List.of(
            "https://user:password@mcp.example.com/mcp",
            "https://mcp.example.com/mcp?api_key=secret",
            "https://mcp.example.com/mcp#token",
            "file:///tmp/mcp.sock",
            "ftp://mcp.example.com/mcp");

        for (String url : unsafeUrls) {
            BizException error = assertThrows(BizException.class, () -> service.create(
                new McpSaveRequest("测试MCP", "http", "{\"url\":\"" + url + "\"}", null, 1)));
            assertTrue(error.getMessage().contains("URL"), url);
        }
        verify(mcpMapper, never()).insert(any(AiMcp.class));
    }

    @Test
    void get_shouldExposeTestStatusAndTime() {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("sse");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/sse\"}");
        mcp.setTestStatus(ConnectivityTestStatus.SUCCESS);
        mcp.setTestTime(LocalDateTime.now());
        when(mcpMapper.selectById(1L)).thenReturn(mcp);

        McpVO vo = service.get(1L);

        assertEquals(ConnectivityTestStatus.SUCCESS, vo.getTestStatus());
        assertEquals(List.of("USER", "ADMIN_USER", "API_KEY"), vo.getAllowedSubjectTypes());
    }

    @Test
    void page_shouldNeverExposeRawConfig() {
        AiMcp mcp = mcpWithConfig("{\"url\":\"https://mcp.example.com\",\"headers\":{\"Authorization\":\"Bearer page-secret\"}}");
        Page<AiMcp> dbPage = new Page<>(1, 10);
        dbPage.setRecords(List.of(mcp));
        dbPage.setTotal(1);
        when(mcpMapper.selectPage(any(Page.class), any())).thenReturn(dbPage);

        PageResult<McpVO> result = service.page(new PageQuery());

        assertEquals(1, result.getList().size());
        assertEquals("", result.getList().get(0).getConfig());
        assertFalse(result.getList().get(0).getConfig().contains("page-secret"));
    }

    @Test
    void get_shouldRecursivelyRedactSecretsAndEveryHeaderValue() {
        AiMcp mcp = mcpWithConfig("""
            {"url":"https://mcp.example.com","headers":{"Authorization":"Bearer header-secret","Content-Type":"application/json"},
             "env":{"X-API-Key":"api-secret","clientCredential":"credential-secret","privateKeyPem":"private-key-secret",
                    "accessKeyId":"access-key-secret","nested":{"accessToken":"token-secret"}},"password":"password-secret"}
            """);
        when(mcpMapper.selectById(1L)).thenReturn(mcp);

        String safeConfig = service.get(1L).getConfig();

        assertTrue(safeConfig.contains("https://mcp.example.com"));
        assertTrue(safeConfig.contains(McpConfigProtector.SECRET_PLACEHOLDER));
        assertFalse(safeConfig.contains("header-secret"));
        assertFalse(safeConfig.contains("application/json"));
        assertFalse(safeConfig.contains("api-secret"));
        assertFalse(safeConfig.contains("credential-secret"));
        assertFalse(safeConfig.contains("private-key-secret"));
        assertFalse(safeConfig.contains("access-key-secret"));
        assertFalse(safeConfig.contains("token-secret"));
        assertFalse(safeConfig.contains("password-secret"));
    }

    @Test
    void get_shouldFailClosedForNonObjectStoredConfig() {
        AiMcp mcp = mcpWithConfig("\"stored-secret\"");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);

        String safeConfig = service.get(1L).getConfig();

        assertEquals("{\"redacted\":true,\"reason\":\"invalid config\"}", safeConfig);
        assertFalse(safeConfig.contains("stored-secret"));
    }

    @Test
    void get_shouldFailClosedForLegacyUrlContainingCredentials() {
        AiMcp mcp = mcpWithConfig("{\"url\":\"https://mcp.example.com/mcp?api_key=legacy-secret\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);

        String safeConfig = service.get(1L).getConfig();

        assertEquals("{\"redacted\":true,\"reason\":\"invalid config\"}", safeConfig);
        assertFalse(safeConfig.contains("legacy-secret"));
    }

    @Test
    void update_shouldReuseStoredSecretsOnlyForSameNormalizedEndpoint() {
        AiMcp mcp = mcpWithConfig("""
            {"url":"HTTPS://MCP.EXAMPLE.COM/mcp","headers":{"Authorization":"Bearer old-token","X-Tenant":"tenant-secret"},
             "env":{"CLIENT_SECRET":"old-client-secret"}}
            """);
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        String submitted = """
            {"url":"https://mcp.example.com:443/a/../mcp","headers":{"Authorization":"__MCP_SECRET_REDACTED__","X-Tenant":"__MCP_SECRET_REDACTED__"},
             "env":{"CLIENT_SECRET":"__MCP_SECRET_REDACTED__"}}
            """;

        service.update(1L, new McpSaveRequest("测试MCP", "http", submitted, null, 1));

        ArgumentCaptor<AiMcp> captor = ArgumentCaptor.forClass(AiMcp.class);
        verify(mcpMapper).updateById(captor.capture());
        String persisted = captor.getValue().getConfig();
        assertTrue(persisted.contains("https://mcp.example.com:443/a/../mcp"));
        assertTrue(persisted.contains("Bearer old-token"));
        assertTrue(persisted.contains("tenant-secret"));
        assertTrue(persisted.contains("old-client-secret"));
        assertFalse(persisted.contains(McpConfigProtector.SECRET_PLACEHOLDER));
    }

    @Test
    void update_shouldRejectOldSecretReuseAfterRemoteEndpointChange() {
        AiMcp mcp = mcpWithConfig("""
            {"url":"https://trusted.example.com/mcp","headers":{"Authorization":"Bearer old-token"}}
            """);
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        String submitted = """
            {"url":"https://attacker.example.com/mcp","headers":{"Authorization":"__MCP_SECRET_REDACTED__"}}
            """;

        BizException error = assertThrows(BizException.class,
            () -> service.update(1L, new McpSaveRequest("测试MCP", "http", submitted, null, 1)));

        assertTrue(error.getMessage().contains("连接目标已变化"));
        assertTrue(error.getMessage().contains("重新提供全部凭据"));
        verify(mcpMapper, never()).updateById(any(AiMcp.class));
    }

    @Test
    void update_shouldAllowEndpointChangeWhenAllCredentialsAreResubmitted() {
        AiMcp mcp = mcpWithConfig(
            "{\"url\":\"https://old.example.com/mcp\",\"headers\":{\"Authorization\":\"old-token\"}}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        String submitted =
            "{\"url\":\"https://new.example.com/mcp\",\"headers\":{\"Authorization\":\"new-token\"}}";

        service.update(1L, new McpSaveRequest("测试MCP", "http", submitted, null, 1));

        ArgumentCaptor<AiMcp> captor = ArgumentCaptor.forClass(AiMcp.class);
        verify(mcpMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getConfig().contains("https://new.example.com/mcp"));
        assertTrue(captor.getValue().getConfig().contains("new-token"));
        assertFalse(captor.getValue().getConfig().contains("old-token"));
    }

    @Test
    void update_shouldRejectOldSecretReuseAfterRemoteTransportTypeChange() {
        AiMcp mcp = mcpWithConfig("{\"url\":\"https://trusted.example.com/mcp\",\"headers\":{\"Authorization\":\"old\"}}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        String submitted = "{\"url\":\"https://trusted.example.com/mcp\",\"headers\":{\"Authorization\":\"__MCP_SECRET_REDACTED__\"}}";

        BizException error = assertThrows(BizException.class,
            () -> service.update(1L, new McpSaveRequest("测试MCP", "sse", submitted, null, 1)));

        assertTrue(error.getMessage().contains("连接目标已变化"));
        verify(mcpMapper, never()).updateById(any(AiMcp.class));
    }

    @Test
    void update_shouldRejectOldSecretReuseAfterStdioExecutionTargetChange() {
        List<String> changedConfigs = List.of(
            "{\"command\":\"node\",\"args\":[\"server.js\"],\"cwd\":\"/srv/mcp\",\"env\":{\"TOKEN\":\"__MCP_SECRET_REDACTED__\"}}",
            "{\"command\":\"python\",\"args\":[\"attacker.py\"],\"cwd\":\"/srv/mcp\",\"env\":{\"TOKEN\":\"__MCP_SECRET_REDACTED__\"}}",
            "{\"command\":\"python\",\"args\":[\"server.py\"],\"cwd\":\"/tmp/attacker\",\"env\":{\"TOKEN\":\"__MCP_SECRET_REDACTED__\"}}");

        for (String submitted : changedConfigs) {
            AiMcp mcp = mcpWithConfig("stdio",
                "{\"command\":\"python\",\"args\":[\"server.py\"],\"cwd\":\"/srv/mcp\",\"env\":{\"TOKEN\":\"old-token\"}}");
            when(mcpMapper.selectById(1L)).thenReturn(mcp);

            BizException error = assertThrows(BizException.class,
                () -> service.update(1L, new McpSaveRequest("测试MCP", "stdio", submitted, null, 1)));

            assertTrue(error.getMessage().contains("连接目标已变化"), submitted);
        }
        verify(mcpMapper, never()).updateById(any(AiMcp.class));
    }

    @Test
    void update_shouldReuseStdioSecretForUnchangedExecutionTarget() {
        AiMcp mcp = mcpWithConfig("stdio",
            "{\"command\":\"python\",\"args\":[\"server.py\"],\"workingDirectory\":\"/srv/mcp\",\"env\":{\"TOKEN\":\"old-token\"}}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        String submitted =
            "{\"command\":\"python\",\"args\":[\"server.py\"],\"cwd\":\"/srv/mcp\",\"env\":{\"TOKEN\":\"__MCP_SECRET_REDACTED__\"}}";

        service.update(1L, new McpSaveRequest("测试MCP", "stdio", submitted, null, 1));

        ArgumentCaptor<AiMcp> captor = ArgumentCaptor.forClass(AiMcp.class);
        verify(mcpMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getConfig().contains("old-token"));
        assertFalse(captor.getValue().getConfig().contains(McpConfigProtector.SECRET_PLACEHOLDER));
    }

    @Test
    void create_shouldRejectRedactedPlaceholder() {
        McpSaveRequest request = new McpSaveRequest("复制MCP", "http",
            "{\"headers\":{\"Authorization\":\"__MCP_SECRET_REDACTED__\"}}", null, 1);

        BizException error = assertThrows(BizException.class, () -> service.create(request));

        assertTrue(error.getMessage().contains("重新提供 secret"));
    }

    @Test
    void update_shouldRejectPlaceholderOutsideProtectedPosition() {
        AiMcp mcp = mcpWithConfig("{\"url\":\"https://mcp.example.com\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        McpSaveRequest request = new McpSaveRequest("测试MCP", "http",
            "{\"url\":\"__MCP_SECRET_REDACTED__\"}", null, 1);

        BizException error = assertThrows(BizException.class, () -> service.update(1L, request));

        assertTrue(error.getMessage().contains("只能用于敏感字段"));
    }

    @Test
    void testConnectivity_shouldPersistResultWithinCapturedTenant() throws Exception {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setTenantId("tenant-a");
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("http");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/mcp\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(mcpFactory.testConnectivity(anyString(), anyString(), anyString()))
            .thenReturn(new McpTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null));
        AtomicReference<String> persistedTenant = new AtomicReference<>();
        when(mcpMapper.updateById(any(AiMcp.class))).thenAnswer(invocation -> {
            persistedTenant.set(TenantContext.get());
            return 1;
        });

        TenantContext.set("tenant-a");
        McpTestResult result;
        try {
            CompletableFuture<McpTestResult> future = service.testConnectivity(1L);
            result = future.get();
        } finally {
            TenantContext.clear();
        }

        assertEquals(ConnectivityTestStatus.SUCCESS, result.testStatus());
        assertEquals("tenant-a", persistedTenant.get());
        verify(mcpMapper).updateById(org.mockito.ArgumentMatchers.any(AiMcp.class));
    }

    @Test
    void delete_shouldRejectUnknownId() {
        when(mcpMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.delete(999L));
    }

    @Test
    void listDebugTools_shouldReturnTools_whenFactorySucceeds() throws Exception {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("http");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/mcp\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        McpDebugToolVO tool = new McpDebugToolVO("get_attendance", "查考勤", "object", Map.of(), List.of());
        when(mcpFactory.listDebugTools(anyString(), anyString(), anyString())).thenReturn(List.of(tool));

        List<McpDebugToolVO> tools = service.listDebugTools(1L).get();

        assertEquals(1, tools.size());
        assertEquals("get_attendance", tools.get(0).name());
    }

    /** 连不上/握手失败时，listDebugTools 应该让 CompletableFuture 以 BizException 收场，而不是返回空列表——
     * 空列表和"这个 MCP 真的没有工具"分不清，必须让前端能明确区分"失败"和"没有工具"。 */
    @Test
    void listDebugTools_shouldFailFuture_whenFactoryThrows() throws Exception {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("http");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/mcp\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(mcpFactory.listDebugTools(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("connection refused"));

        CompletableFuture<List<McpDebugToolVO>> future = service.listDebugTools(1L);

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof BizException);
    }

    @Test
    void callDebugTool_shouldReturnSuccessResult() throws Exception {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("http");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/mcp\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(mcpFactory.callDebugTool(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new McpDebugCallResult(true, "今日出勤：09:02 打卡", null, List.of(), false));

        McpDebugCallResult result = service.callDebugTool(1L, new McpDebugCallRequest("get_attendance", Map.of())).get();

        assertTrue(result.success());
        assertEquals("今日出勤：09:02 打卡", result.output());
    }

    /** 跟 listDebugTools 不同：单次工具调用失败不该让整个请求 500，而是原样落在返回体的
     * McpDebugCallResult 里（success=false + errorMessage），前端不用区分 catch 和 then 两条路径。 */
    @Test
    void callDebugTool_shouldReturnFailureResult_whenFactoryThrows() throws Exception {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("http");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/mcp\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(mcpFactory.callDebugTool(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("tool not found"));

        McpDebugCallResult result = service.callDebugTool(1L, new McpDebugCallRequest("unknown_tool", Map.of())).get();

        assertFalse(result.success());
        assertEquals("tool not found", result.errorMessage());
    }

    private AiMcp mcpWithConfig(String config) {
        return mcpWithConfig("http", config);
    }

    private AiMcp mcpWithConfig(String mcpType, String config) {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType(mcpType);
        mcp.setConfig(config);
        return mcp;
    }
}
