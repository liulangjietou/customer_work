package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

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
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpService} 单测：mcpType 校验（含新增的 http）、连通性测试装配、脱敏无关字段回显。
 * @author owlzhangfq@gmail.com
 */
class McpServiceTest {

    private AiMcpMapper mcpMapper;
    private AdminMcpFactory mcpFactory;
    private McpService service;

    @BeforeEach
    void setUp() {
        mcpMapper = mock(AiMcpMapper.class);
        AiAgentMcpMapper agentMcpMapper = mock(AiAgentMcpMapper.class);
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        AgentInstanceCache agentInstanceCache = mock(AgentInstanceCache.class);
        mcpFactory = mock(AdminMcpFactory.class);
        service = new McpService(mcpMapper, agentMcpMapper, agentMapper, agentInstanceCache, mcpFactory);
    }

    @Test
    void create_shouldAcceptHttpType() {
        McpSaveRequest request = new McpSaveRequest("测试MCP", "http", "{\"url\": \"https://mcp.example.com/mcp\"}", null, 1);

        service.create(request);

        verify(mcpMapper).insert(org.mockito.ArgumentMatchers.any(AiMcp.class));
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
    void get_shouldExposeTestStatusAndTime() {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("sse");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/sse\"}");
        mcp.setTestStatus(McpTestResult.STATUS_SUCCESS);
        mcp.setTestTime(LocalDateTime.now());
        when(mcpMapper.selectById(1L)).thenReturn(mcp);

        McpVO vo = service.get(1L);

        assertEquals(McpTestResult.STATUS_SUCCESS, vo.getTestStatus());
    }

    @Test
    void testConnectivity_shouldPersistResult() throws Exception {
        AiMcp mcp = new AiMcp();
        mcp.setId(1L);
        mcp.setMcpName("测试MCP");
        mcp.setMcpType("http");
        mcp.setConfig("{\"url\": \"https://mcp.example.com/mcp\"}");
        when(mcpMapper.selectById(1L)).thenReturn(mcp);
        when(mcpFactory.testConnectivity(anyString(), anyString(), anyString()))
            .thenReturn(new McpTestResult(McpTestResult.STATUS_SUCCESS, LocalDateTime.now(), null));

        CompletableFuture<McpTestResult> future = service.testConnectivity(1L);
        McpTestResult result = future.get();

        assertEquals(McpTestResult.STATUS_SUCCESS, result.testStatus());
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
}
