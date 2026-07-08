package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * MCP 管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class McpService {

    private static final Set<String> VALID_MCP_TYPES = Set.of("stdio", "sse");

    private final AiMcpMapper mcpMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpService(AiMcpMapper mcpMapper, AiAgentMcpMapper agentMcpMapper) {
        this.mcpMapper = mcpMapper;
        this.agentMcpMapper = agentMcpMapper;
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
        mcpMapper.insert(mcp);
    }

    public void update(Long id, McpSaveRequest request) {
        AiMcp mcp = requireMcp(id);
        validate(request);
        fillFromRequest(mcp, request);
        mcpMapper.updateById(mcp);
    }

    public void delete(Long id) {
        requireMcp(id);
        if (agentMcpMapper.exists(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getMcpId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该 MCP 正被智能体引用，无法删除");
        }
        mcpMapper.deleteById(id);
    }

    /** mcpType 仅接受 stdio/sse；config 须为合法 JSON（一处防御式校验，供 create/update 复用）。 */
    private void validate(McpSaveRequest request) {
        if (!VALID_MCP_TYPES.contains(request.mcpType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "mcpType 仅支持 stdio/sse");
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
