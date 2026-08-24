package com.richard.fyoung.customeradmin.aiconfig.systemtool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.dto.SystemToolSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.dto.SystemToolVO;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiAgentSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统工具管理：工具目录是代码定义的（{@code tool_code} 对应一个 Spring Bean），库里只维护启停状态与
 * 展示信息，故只有分页查询 + 编辑（改名称/描述/启停/备注），没有新建/删除。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SystemToolService {

    private final AiSystemToolMapper systemToolMapper;
    private final AiAgentSystemToolMapper agentSystemToolMapper;
    private final AiAgentMapper agentMapper;
    private final AgentInstanceCache agentInstanceCache;

    public SystemToolService(AiSystemToolMapper systemToolMapper, AiAgentSystemToolMapper agentSystemToolMapper,
                             AiAgentMapper agentMapper, AgentInstanceCache agentInstanceCache) {
        this.systemToolMapper = systemToolMapper;
        this.agentSystemToolMapper = agentSystemToolMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceCache = agentInstanceCache;
    }

    public PageResult<SystemToolVO> page(PageQuery query) {
        LambdaQueryWrapper<AiSystemTool> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiSystemTool::getToolName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiSystemTool::getEnabled, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiSystemTool::getCreateTime);

        IPage<AiSystemTool> page = systemToolMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public SystemToolVO get(Long id) {
        return toVo(requireTool(id));
    }

    public void update(Long id, SystemToolSaveRequest request) {
        AiSystemTool tool = requireTool(id);
        tool.setToolName(request.toolName());
        tool.setDescription(request.description());
        tool.setEnabled(request.enabled() == null ? 1 : request.enabled());
        tool.setRemark(request.remark());
        systemToolMapper.updateById(tool);
        evictAgentsReferencingTool(id);
    }

    /** 系统工具启停/信息变更会影响引用它的智能体运行时装配（启用与否决定是否注册进 Toolkit），需一并失效重建。 */
    private void evictAgentsReferencingTool(Long systemToolId) {
        List<Long> agentIds = agentSystemToolMapper.selectList(
                new LambdaQueryWrapper<AiAgentSystemTool>().eq(AiAgentSystemTool::getSystemToolId, systemToolId))
            .stream().map(AiAgentSystemTool::getAgentId).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(agentIds)) {
            return;
        }
        List<String> agentCodes = agentMapper.selectBatchIds(agentIds).stream()
            .map(AiAgent::getAgentCode).collect(Collectors.toList());
        agentInstanceCache.invalidateAll(agentCodes);
    }

    private SystemToolVO toVo(AiSystemTool tool) {
        SystemToolVO vo = new SystemToolVO();
        vo.setId(tool.getId());
        vo.setToolCode(tool.getToolCode());
        vo.setToolName(tool.getToolName());
        vo.setDescription(tool.getDescription());
        vo.setEnabled(tool.getEnabled());
        vo.setRemark(tool.getRemark());
        vo.setCreateTime(tool.getCreateTime());
        vo.setUpdateTime(tool.getUpdateTime());
        return vo;
    }

    private AiSystemTool requireTool(Long id) {
        AiSystemTool tool = systemToolMapper.selectById(id);
        if (tool == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "系统工具不存在: " + id);
        }
        return tool;
    }
}
