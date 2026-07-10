package com.richard.fyoung.customeradmin.workspace.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatSessionSummary;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatHistoryService;
import com.richard.fyoung.customeradmin.workspace.project.dto.AddSessionRequest;
import com.richard.fyoung.customeradmin.workspace.project.dto.ProjectSaveRequest;
import com.richard.fyoung.customeradmin.workspace.project.dto.ProjectSessionVO;
import com.richard.fyoung.customeradmin.workspace.project.dto.ProjectVO;
import com.richard.fyoung.customeradmin.workspace.project.entity.AiProject;
import com.richard.fyoung.customeradmin.workspace.project.entity.AiProjectSession;
import com.richard.fyoung.customeradmin.workspace.project.mapper.AiProjectMapper;
import com.richard.fyoung.customeradmin.workspace.project.mapper.AiProjectSessionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Projects：跨智能体的会话分组管理。
 *
 * <p>权限点复用 {@code workspace}（跟一级菜单"智能体工作区"同一使用场景），不区分"谁能管理项目"与
 * "谁能把会话加进项目"——项目管理不是需要精细化 RBAC 收紧的敏感操作，见 V11 迁移注释。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ProjectService {

    private final AiProjectMapper projectMapper;
    private final AiProjectSessionMapper projectSessionMapper;
    private final AiAgentMapper agentMapper;
    private final ChatHistoryService chatHistoryService;

    public ProjectService(AiProjectMapper projectMapper, AiProjectSessionMapper projectSessionMapper,
                           AiAgentMapper agentMapper, ChatHistoryService chatHistoryService) {
        this.projectMapper = projectMapper;
        this.projectSessionMapper = projectSessionMapper;
        this.agentMapper = agentMapper;
        this.chatHistoryService = chatHistoryService;
    }

    /** 项目数量通常不大（跟角色/智能体一个量级），列表 + 挑选器共用同一个不分页接口，简单直接。 */
    public List<ProjectVO> list(String keyword) {
        LambdaQueryWrapper<AiProject> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(AiProject::getProjectName, keyword);
        }
        wrapper.orderByDesc(AiProject::getCreateTime);
        List<AiProject> projects = projectMapper.selectList(wrapper);
        if (projects.isEmpty()) {
            return List.of();
        }

        List<Long> projectIds = projects.stream().map(AiProject::getId).collect(Collectors.toList());
        Map<Long, Long> countByProject = projectSessionMapper.selectList(
                new LambdaQueryWrapper<AiProjectSession>().in(AiProjectSession::getProjectId, projectIds))
            .stream().collect(Collectors.groupingBy(AiProjectSession::getProjectId, Collectors.counting()));

        return projects.stream().map(p -> toVo(p, countByProject.getOrDefault(p.getId(), 0L))).collect(Collectors.toList());
    }

    public void create(ProjectSaveRequest request) {
        AiProject project = new AiProject();
        project.setProjectName(request.projectName());
        project.setDescription(request.description());
        projectMapper.insert(project);
    }

    public void update(Long id, ProjectSaveRequest request) {
        AiProject project = requireProject(id);
        project.setProjectName(request.projectName());
        project.setDescription(request.description());
        projectMapper.updateById(project);
    }

    /** 删除项目连带清掉关联的会话条目——项目-会话关联"属于"项目本身，不是独立有价值的数据，级联删不用二次确认。 */
    @Transactional
    public void delete(Long id) {
        requireProject(id);
        projectSessionMapper.delete(new LambdaQueryWrapper<AiProjectSession>().eq(AiProjectSession::getProjectId, id));
        projectMapper.deleteById(id);
    }

    /** 项目详情：逐条把关联会话解析成"预览+所属智能体+时间"，会话已经查不到内容的标 stale，不抛错、不中断整个列表。 */
    public List<ProjectSessionVO> listSessions(Long id) {
        requireProject(id);
        List<AiProjectSession> links = projectSessionMapper.selectList(
            new LambdaQueryWrapper<AiProjectSession>().eq(AiProjectSession::getProjectId, id)
                .orderByDesc(AiProjectSession::getCreateTime));
        if (links.isEmpty()) {
            return List.of();
        }

        Map<String, String> agentNameByCode = resolveAgentNames(links);
        return links.stream().map(link -> toSessionVo(link, agentNameByCode)).collect(Collectors.toList());
    }

    /** 幂等：同一会话重复加入同一项目不报错，直接当已经在项目里处理（前端不用先查一遍再决定按钮态）。 */
    public void addSession(Long id, AddSessionRequest request) {
        requireProject(id);
        AiProjectSession link = new AiProjectSession();
        link.setProjectId(id);
        link.setAgentCode(request.agentCode());
        link.setSessionId(request.sessionId());
        try {
            projectSessionMapper.insert(link);
        } catch (DuplicateKeyException ignored) {
            // 已经在项目里了，视为成功
        }
    }

    public void removeSession(Long id, String agentCode, String sessionId) {
        projectSessionMapper.delete(new LambdaQueryWrapper<AiProjectSession>()
            .eq(AiProjectSession::getProjectId, id)
            .eq(AiProjectSession::getAgentCode, agentCode)
            .eq(AiProjectSession::getSessionId, sessionId));
    }

    private Map<String, String> resolveAgentNames(List<AiProjectSession> links) {
        List<String> agentCodes = links.stream().map(AiProjectSession::getAgentCode).distinct().collect(Collectors.toList());
        Map<String, String> result = new HashMap<>();
        agentMapper.selectList(new LambdaQueryWrapper<AiAgent>().in(AiAgent::getAgentCode, agentCodes))
            .forEach(a -> result.put(a.getAgentCode(), a.getAgentName()));
        return result;
    }

    private ProjectSessionVO toSessionVo(AiProjectSession link, Map<String, String> agentNameByCode) {
        ProjectSessionVO vo = new ProjectSessionVO();
        vo.setAgentCode(link.getAgentCode());
        vo.setAgentName(agentNameByCode.getOrDefault(link.getAgentCode(), link.getAgentCode()));
        vo.setSessionId(link.getSessionId());
        vo.setAddedTime(link.getCreateTime());

        Optional<ChatSessionSummary> summary = chatHistoryService.getSessionSummary(link.getAgentCode(), link.getSessionId());
        if (summary.isPresent()) {
            vo.setPreview(summary.get().preview());
            vo.setLastMessageTime(summary.get().lastMessageTime());
            vo.setMessageCount(summary.get().messageCount());
            vo.setStale(false);
        } else {
            vo.setStale(true);
        }
        return vo;
    }

    private ProjectVO toVo(AiProject p, long sessionCount) {
        ProjectVO vo = new ProjectVO();
        vo.setId(p.getId());
        vo.setProjectName(p.getProjectName());
        vo.setDescription(p.getDescription());
        vo.setSessionCount((int) sessionCount);
        vo.setCreateTime(p.getCreateTime());
        return vo;
    }

    private AiProject requireProject(Long id) {
        AiProject project = projectMapper.selectById(id);
        if (project == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "项目不存在: " + id);
        }
        return project;
    }
}
