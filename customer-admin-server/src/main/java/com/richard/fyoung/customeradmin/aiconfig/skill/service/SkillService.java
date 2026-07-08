package com.richard.fyoung.customeradmin.aiconfig.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.skill.dto.SkillVO;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Skill 管理。
 * @author owlzhangfq@gmail.com
 */
@Service
public class SkillService {

    private final AiSkillMapper skillMapper;
    private final AiAgentSkillMapper agentSkillMapper;
    private final AiAgentMapper agentMapper;
    private final AgentInstanceCache agentInstanceCache;

    public SkillService(AiSkillMapper skillMapper, AiAgentSkillMapper agentSkillMapper, AiAgentMapper agentMapper,
                         AgentInstanceCache agentInstanceCache) {
        this.skillMapper = skillMapper;
        this.agentSkillMapper = agentSkillMapper;
        this.agentMapper = agentMapper;
        this.agentInstanceCache = agentInstanceCache;
    }

    public PageResult<SkillVO> page(PageQuery query) {
        LambdaQueryWrapper<AiSkill> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiSkill::getSkillName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiSkill::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiSkill::getCreateTime);

        IPage<AiSkill> page = skillMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public SkillVO get(Long id) {
        return toVo(requireSkill(id));
    }

    public void create(SkillSaveRequest request) {
        if (skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        AiSkill skill = new AiSkill();
        fillFromRequest(skill, request);
        skillMapper.insert(skill);
    }

    public void update(Long id, SkillSaveRequest request) {
        AiSkill skill = requireSkill(id);
        if (!skill.getSkillCode().equals(request.skillCode())
            && skillMapper.exists(new LambdaQueryWrapper<AiSkill>().eq(AiSkill::getSkillCode, request.skillCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "技能编码已存在");
        }
        fillFromRequest(skill, request);
        skillMapper.updateById(skill);
        evictAgentsReferencingSkill(id);
    }

    /** Skill 内容变更（content 等）会让引用它的智能体运行时用上旧 SKILL.md，需一并失效。 */
    private void evictAgentsReferencingSkill(Long skillId) {
        List<Long> agentIds = agentSkillMapper.selectList(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getSkillId, skillId))
            .stream().map(AiAgentSkill::getAgentId).collect(Collectors.toList());
        if (agentIds.isEmpty()) {
            return;
        }
        List<String> agentCodes = agentMapper.selectBatchIds(agentIds).stream()
            .map(AiAgent::getAgentCode).collect(Collectors.toList());
        agentInstanceCache.evictAll(agentCodes);
    }

    public void delete(Long id) {
        requireSkill(id);
        if (agentSkillMapper.exists(new LambdaQueryWrapper<AiAgentSkill>().eq(AiAgentSkill::getSkillId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该 Skill 正被智能体引用，无法删除");
        }
        skillMapper.deleteById(id);
    }

    private void fillFromRequest(AiSkill skill, SkillSaveRequest request) {
        skill.setSkillName(request.skillName());
        skill.setSkillCode(request.skillCode());
        skill.setContent(request.content());
        skill.setDescription(request.description());
        skill.setStatus(request.status() == null ? 1 : request.status());
    }

    private SkillVO toVo(AiSkill skill) {
        SkillVO vo = new SkillVO();
        vo.setId(skill.getId());
        vo.setSkillName(skill.getSkillName());
        vo.setSkillCode(skill.getSkillCode());
        vo.setContent(skill.getContent());
        vo.setDescription(skill.getDescription());
        vo.setStatus(skill.getStatus());
        vo.setCreateTime(skill.getCreateTime());
        return vo;
    }

    private AiSkill requireSkill(Long id) {
        AiSkill skill = skillMapper.selectById(id);
        if (skill == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "Skill 不存在: " + id);
        }
        return skill;
    }
}
