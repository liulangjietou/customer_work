package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
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
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * MCP 管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class McpService {

    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private static final Set<String> VALID_MCP_TYPES = Set.of("stdio", "sse", "http");
    /** 连通性测试专用线程池：与模型测试同一手法，独立于 Tomcat 请求线程（实施计划 §五）。 */
    private static final ExecutorService MCP_TEST_EXECUTOR = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "mcp-test-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final long TEST_FUTURE_TIMEOUT_SECONDS = 10;

    private final AiMcpMapper mcpMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiAgentMapper agentMapper;
    private final AgentInstanceCache agentInstanceCache;
    private final AdminMcpFactory mcpFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpService(AiMcpMapper mcpMapper, AiAgentMcpMapper agentMcpMapper, AiAgentMapper agentMapper,
                       AgentInstanceCache agentInstanceCache, AdminMcpFactory mcpFactory) {
        this.mcpMapper = mcpMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceCache = agentInstanceCache;
        this.mcpFactory = mcpFactory;
    }

    public PageResult<McpVO> page(PageQuery query) {
        LambdaQueryWrapper<AiMcp> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiMcp::getMcpName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiMcp::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiMcp::getCreateTime);

        IPage<AiMcp> page = mcpMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public McpVO get(Long id) {
        return toVo(requireMcp(id));
    }

    public void create(McpSaveRequest request) {
        validate(request);
        AiMcp mcp = new AiMcp();
        fillFromRequest(mcp, request);
        mcp.setTestStatus(ConnectivityTestStatus.UNTESTED);
        mcpMapper.insert(mcp);
    }

    public void update(Long id, McpSaveRequest request) {
        AiMcp mcp = requireMcp(id);
        validate(request);
        fillFromRequest(mcp, request);
        mcpMapper.updateById(mcp);
        evictAgentsReferencingMcp(id);
    }

    /** MCP 连接配置变更（url/command 等）会让引用它的智能体运行时用上旧配置，需一并失效。 */
    private void evictAgentsReferencingMcp(Long mcpId) {
        List<Long> agentIds = agentMcpMapper.selectList(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getMcpId, mcpId))
            .stream().map(AiAgentMcp::getAgentId).collect(Collectors.toList());
        if (agentIds.isEmpty()) {
            return;
        }
        List<String> agentCodes = agentMapper.selectBatchIds(agentIds).stream()
            .map(AiAgent::getAgentCode).collect(Collectors.toList());
        agentInstanceCache.evictAll(agentCodes);
    }

    public void delete(Long id) {
        requireMcp(id);
        if (agentMcpMapper.exists(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getMcpId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该 MCP 正被智能体引用，无法删除");
        }
        mcpMapper.deleteById(id);
    }

    /**
     * 连通性测试：与模型测试同一手法，派发到独立线程池执行，硬性超时兜底，不占用调用方（Tomcat）线程。
     */
    public CompletableFuture<McpTestResult> testConnectivity(Long id) {
        AiMcp mcp = requireMcp(id);

        return CompletableFuture
            .supplyAsync(() -> mcpFactory.testConnectivity(mcp.getMcpName(), mcp.getMcpType(), mcp.getConfig()), MCP_TEST_EXECUTOR)
            .orTimeout(TEST_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex.getCause() instanceof TimeoutException || ex instanceof TimeoutException) {
                    log.error("mcp connectivity test hard-timeout, code={}, mcpId={}", "MCP-TEST-HARD-TIMEOUT", id, ex);
                } else {
                    log.error("mcp connectivity test unexpected error, code={}, mcpId={}", "MCP-TEST-UNEXPECTED", id, ex);
                }
                return new McpTestResult(ConnectivityTestStatus.FAILED, LocalDateTime.now(), "连通性测试超时或执行异常");
            })
            .thenApply(result -> {
                persistTestResult(id, result);
                return result;
            });
    }

    /**
     * 调试面板 · 列工具：跟连通性测试同一手法派发到独立线程池，避免 {@code AdminMcpFactory} 内部的
     * {@code .block()} 占用 Tomcat 请求线程；异常统一包成 {@link BizException}，让前端拿到具体错误文案
     * 而不是泛泛的 500。
     */
    public CompletableFuture<List<McpDebugToolVO>> listDebugTools(Long id) {
        AiMcp mcp = requireMcp(id);
        return CompletableFuture
            .supplyAsync(() -> {
                try {
                    return mcpFactory.listDebugTools(mcp.getMcpName(), mcp.getMcpType(), mcp.getConfig());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, MCP_TEST_EXECUTOR)
            .orTimeout(TEST_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                log.error("mcp debug listTools failed, code={}, mcpId={}", "MCP-DEBUG-LIST-FAIL", id, ex);
                throw new BizException(ResultCode.MCP_CONNECT_FAILED, describeDebugError(ex));
            });
    }

    /** 调试面板 · 单次调用工具：同上分发到独立线程池；工具调用本身可能有副作用（比如真的下了单/查了数据），不做重试。 */
    public CompletableFuture<McpDebugCallResult> callDebugTool(Long id, McpDebugCallRequest request) {
        AiMcp mcp = requireMcp(id);
        return CompletableFuture
            .supplyAsync(() -> {
                try {
                    return mcpFactory.callDebugTool(mcp.getMcpName(), mcp.getMcpType(), mcp.getConfig(),
                        request.toolName(), request.arguments());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, MCP_TEST_EXECUTOR)
            .orTimeout(TEST_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> new McpDebugCallResult(false, null, describeDebugError(ex), List.of(), false));
    }

    /** 一路走到根因——{@code supplyAsync} 里 checked Exception 包一层 RuntimeException，
     * CompletableFuture 内部又会再包一层 CompletionException，不沿着 cause 链走到底，
     * 拿到的会是外层包装类自己的 toString()，不是真正有意义的错误信息。 */
    private String describeDebugError(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "调试请求超时";
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private void persistTestResult(Long id, McpTestResult result) {
        AiMcp update = new AiMcp();
        update.setId(id);
        update.setTestStatus(result.testStatus());
        update.setTestTime(result.testTime());
        mcpMapper.updateById(update);
    }

    /** mcpType 仅接受 stdio/sse/http；config 须为合法 JSON（一处防御式校验，供 create/update 复用）。 */
    private void validate(McpSaveRequest request) {
        if (!VALID_MCP_TYPES.contains(request.mcpType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "mcpType 仅支持 stdio/sse/http");
        }
        try {
            objectMapper.readTree(request.config());
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "config 不是合法 JSON: " + e.getMessage());
        }
    }

    private void fillFromRequest(AiMcp mcp, McpSaveRequest request) {
        mcp.setMcpName(request.mcpName());
        mcp.setMcpType(request.mcpType());
        mcp.setConfig(request.config());
        mcp.setDescription(request.description());
        mcp.setStatus(request.status() == null ? 1 : request.status());
    }

    private McpVO toVo(AiMcp mcp) {
        McpVO vo = new McpVO();
        vo.setId(mcp.getId());
        vo.setMcpName(mcp.getMcpName());
        vo.setMcpType(mcp.getMcpType());
        vo.setConfig(mcp.getConfig());
        vo.setDescription(mcp.getDescription());
        vo.setStatus(mcp.getStatus());
        vo.setTestStatus(mcp.getTestStatus());
        vo.setTestTime(mcp.getTestTime());
        vo.setCreateTime(mcp.getCreateTime());
        return vo;
    }

    private AiMcp requireMcp(Long id) {
        AiMcp mcp = mcpMapper.selectById(id);
        if (mcp == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "MCP 不存在: " + id);
        }
        return mcp;
    }
}
