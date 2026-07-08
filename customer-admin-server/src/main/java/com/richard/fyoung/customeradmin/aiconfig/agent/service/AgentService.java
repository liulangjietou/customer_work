package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 智能体管理：CRUD + 关联表（MCP/Skill）整体替换式维护 + 启用停用生命周期。
 * 智能体可见性变化（新建/编辑/删除/启停）都会影响动态菜单，统一在此处 {@link MenuVersionHolder#bump()}。
 * @author owlzhangfq@gmail.com
 */
@Service
public class AgentService {

    private static final Pattern AGENT_CODE_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Set<String> VALID_CAPABILITIES = Set.of("chat", "vibecoding");
    private static final String CAPABILITY_DELIMITER = ",";

    private final AiAgentMapper agentMapper;
    private final AiAgentMcpMapper agentMcpMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final AiMcpMapper mcpMapper;
    private final AiSkillMapper skillMapper;
    private final MenuVersionHolder menuVersionHolder;
    private final AgentInstanceCache agentInstanceCache;

    public AgentService(AiAgentMapper agentMapper, AiAgentMcpMapper agentMcpMapper,
                         AiAgentSkillMapper agentSkillMapper, AiModelConfigMapper modelConfigMapper,
                         AiMcpMapper mcpMapper, AiSkillMapper skillMapper, MenuVersionHolder menuVersionHolder,
                         AgentInstanceCache agentInstanceCache) {
        this.agentMapper = agentMapper;
        this.agentMcpMapper = agentMcpMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.mcpMapper = mcpMapper;
        this.skillMapper = skillMapper;
        this.menuVersionHolder = menuVersionHolder;
        this.agentInstanceCache = agentInstanceCache;
    }

    public PageResult<AgentVO> page(PageQuery query) {
        LambdaQueryWrapper<AiAgent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiAgent::getAgentName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiAgent::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiAgent::getCreateTime);

        IPage<AiAgent> page = agentMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public AgentVO get(Long id) {
        return toVo(requireAgent(id));
    }

    /** 返回当前启用中的智能体（动态菜单聚合用，不分页）。 */
    public List<AiAgent> listEnabled() {
        LambdaQueryWrapper<AiAgent> wrapper = new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getStatus, 1);
        return agentMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(AgentSaveRequest request) {
        validate(request);
        AiAgent agent = new AiAgent();
        fillFromRequest(agent, request);
        agentMapper.insert(agent);
        replaceRelations(agent.getId(), request.mcpIds(), request.skillIds());
        menuVersionHolder.bump();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AgentSaveRequest request) {
        AiAgent agent = requireAgent(id);
        String oldAgentCode = agent.getAgentCode();
        validate(request);
        fillFromRequest(agent, request);
        agentMapper.updateById(agent);
        replaceRelations(id, request.mcpIds(), request.skillIds());
        menuVersionHolder.bump();
        // agentCode 理论上不应改变，但仍按新旧两个 code 双清，避免缓存键错位残留旧实例
        agentInstanceCache.evict(oldAgentCode);
        agentInstanceCache.evict(request.agentCode());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiAgent agent = requireAgent(id);
        agentMapper.deleteById(id);
        agentMcpMapper.delete(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, id));
        agentSkillMapper.delete(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, id));
        menuVersionHolder.bump();
        agentInstanceCache.evict(agent.getAgentCode());
    }

    /** 启用/停用（生命周期），不改动其余字段。 */
    public void updateStatus(Long id, int status) {
        AiAgent agent = requireAgent(id);
        AiAgent update = new AiAgent();
        update.setId(id);
        update.setStatus(status);
        agentMapper.updateById(update);
        menuVersionHolder.bump();
        agentInstanceCache.evict(agent.getAgentCode());
    }

    /** modelId 必须引用真实存在的模型；mcpIds/skillIds（若提供）里每个 id 也必须真实存在；agentCode 格式与能力标识做一处防御式校验，供 create/update 复用。 */
    private void validate(AgentSaveRequest request) {
        if (!AGENT_CODE_PATTERN.matcher(request.agentCode()).matches()) {
            throw new BizException(ResultCode.PARAM_INVALID, "agentCode 仅支持小写字母/数字/短横线");
        }
        if (modelConfigMapper.selectById(request.modelId()) == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "modelId 不存在: " + request.modelId());
        }
        if (!CollectionUtils.isEmpty(request.mcpIds())
            && mcpMapper.selectBatchIds(request.mcpIds()).size() != request.mcpIds().size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 mcpIds");
        }
        if (!CollectionUtils.isEmpty(request.skillIds())
            && skillMapper.selectBatchIds(request.skillIds()).size() != request.skillIds().size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "存在无效的 skillIds");
        }
        if (!CollectionUtils.isEmpty(request.capabilities())
            && !VALID_CAPABILITIES.containsAll(request.capabilities())) {
            throw new BizException(ResultCode.PARAM_INVALID, "capabilities 仅支持 chat/vibecoding");
        }
    }

    /** 关联表整体替换：先清空该智能体现有关联行，再按本次提交的 ids 批量插入（比对差异做增量删改无必要，行数很少）。 */
    private void replaceRelations(Long agentId, List<Long> mcpIds, List<Long> skillIds) {
        agentMcpMapper.delete(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(mcpIds)) {
            for (Long mcpId : mcpIds) {
                AiAgentMcp relation = new AiAgentMcp();
                relation.setAgentId(agentId);
                relation.setMcpId(mcpId);
                agentMcpMapper.insert(relation);
            }
        }
        agentSkillMapper.delete(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, agentId));
        if (!CollectionUtils.isEmpty(skillIds)) {
            for (Long skillId : skillIds) {
                AiAgentSkill relation = new AiAgentSkill();
                relation.setAgentId(agentId);
                relation.setSkillId(skillId);
                agentSkillMapper.insert(relation);
            }
        }
    }

    private void fillFromRequest(AiAgent agent, AgentSaveRequest request) {
        agent.setAgentName(request.agentName());
        agent.setAgentCode(request.agentCode());
        agent.setModelId(request.modelId());
        agent.setSystemPrompt(request.systemPrompt());
        agent.setCapabilities(CollectionUtils.isEmpty(request.capabilities())
            ? "chat" : String.join(CAPABILITY_DELIMITER, request.capabilities()));
        agent.setIcon(request.icon());
        agent.setStatus(request.status() == null ? 1 : request.status());
    }

    private AgentVO toVo(AiAgent agent) {
        AgentVO vo = new AgentVO();
        vo.setId(agent.getId());
        vo.setAgentName(agent.getAgentName());
        vo.setAgentCode(agent.getAgentCode());
        vo.setModelId(agent.getModelId());
        AiModelConfig model = modelConfigMapper.selectById(agent.getModelId());
        vo.setModelName(model == null ? null : model.getModelName());
        vo.setMcpIds(agentMcpMapper.selectList(new LambdaQueryWrapper<AiAgentMcp>().eq(AiAgentMcp::getAgentId, agent.getId()))
            .stream().map(AiAgentMcp::getMcpId).collect(Collectors.toList()));
        vo.setSkillIds(agentSkillMapper.selectList(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getAgentId, agent.getId()))
            .stream().map(AiAgentSkill::getSkillId).collect(Collectors.toList()));
        vo.setSystemPrompt(agent.getSystemPrompt());
        vo.setCapabilities(StringUtils.hasText(agent.getCapabilities())
            ? Arrays.asList(agent.getCapabilities().split(CAPABILITY_DELIMITER)) : List.of());
        vo.setIcon(agent.getIcon());
        vo.setStatus(agent.getStatus());
        vo.setCreateTime(agent.getCreateTime());
        return vo;
    }

    private AiAgent requireAgent(Long id) {
        AiAgent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + id);
        }
        return agent;
    }
}
