package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
