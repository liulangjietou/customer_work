package com.richard.fyoung.customeradmin.workspace.project.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectService} 单测：跨智能体会话分组的核心行为——会话数聚合、级联删除、加入幂等、
 * 会话已失效时的 stale 兜底。
 * @author owlzhangfq@gmail.com
 */
class ProjectServiceTest {

    private AiProjectMapper projectMapper;
    private AiProjectSessionMapper projectSessionMapper;
    private AiAgentMapper agentMapper;
    private ChatHistoryService chatHistoryService;
    private ProjectService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiProject.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiProjectSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        projectMapper = mock(AiProjectMapper.class);
        projectSessionMapper = mock(AiProjectSessionMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        chatHistoryService = mock(ChatHistoryService.class);
        service = new ProjectService(projectMapper, projectSessionMapper, agentMapper, chatHistoryService);
    }

    private AiProject project(long id, String name) {
        AiProject p = new AiProject();
        p.setId(id);
        p.setProjectName(name);
        return p;
    }

    @Test
    void list_shouldAggregateSessionCount_perProject() {
        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, "退款专项"), project(2L, "空项目")));
        AiProjectSession s1 = new AiProjectSession();
        s1.setProjectId(1L);
        AiProjectSession s2 = new AiProjectSession();
        s2.setProjectId(1L);
        when(projectSessionMapper.selectList(any())).thenReturn(List.of(s1, s2));

        List<ProjectVO> result = service.list(null);

        assertEquals(2, result.size());
        assertEquals(2, result.get(0).getSessionCount());
        assertEquals(0, result.get(1).getSessionCount());
    }

    @Test
    void create_shouldInsertProject() {
        service.create(new ProjectSaveRequest("退款专项", "跟退款相关的会话"));

        ArgumentCaptor<AiProject> captor = ArgumentCaptor.forClass(AiProject.class);
        verify(projectMapper).insert(captor.capture());
        assertEquals("退款专项", captor.getValue().getProjectName());
    }

    @Test
    void update_shouldRejectUnknownId() {
        when(projectMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.update(999L, new ProjectSaveRequest("x", null)));
    }

    @Test
    void delete_shouldCascadeRemoveSessionLinks_beforeDeletingProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "退款专项"));

        service.delete(1L);

        verify(projectSessionMapper).delete(any());
        verify(projectMapper).deleteById(1L);
    }

    @Test
    void addSession_shouldSwallowDuplicateKeyException_whenAlreadyInProject() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "退款专项"));
        when(projectSessionMapper.insert(any(AiProjectSession.class))).thenThrow(new DuplicateKeyException("dup"));

        // 不抛异常即视为通过——重复加入同一项目应该是幂等的，不是错误
        service.addSession(1L, new AddSessionRequest("oa-assistant", "s1"));
    }

    @Test
    void addSession_shouldRejectUnknownProject() {
        when(projectMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.addSession(999L, new AddSessionRequest("oa-assistant", "s1")));
        verify(projectSessionMapper, never()).insert(any(AiProjectSession.class));
    }

    @Test
    void removeSession_shouldDeleteMatchingLink() {
        service.removeSession(1L, "oa-assistant", "s1");

        verify(projectSessionMapper, times(1)).delete(any());
    }

    @Test
    void listSessions_shouldResolvePreviewAndAgentName() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "退款专项"));
        AiProjectSession link = new AiProjectSession();
        link.setProjectId(1L);
        link.setAgentCode("oa-assistant");
        link.setSessionId("s1");
        when(projectSessionMapper.selectList(any())).thenReturn(List.of(link));
        AiAgent agent = new AiAgent();
        agent.setAgentCode("oa-assistant");
        agent.setAgentName("OA考勤助手");
        when(agentMapper.selectList(any())).thenReturn(List.of(agent));
        when(chatHistoryService.getSessionSummary("oa-assistant", "s1"))
            .thenReturn(Optional.of(new ChatSessionSummary("s1", "查一下我的考勤", "2026-07-10 10:00:00", 4)));

        List<ProjectSessionVO> result = service.listSessions(1L);

        assertEquals(1, result.size());
        ProjectSessionVO vo = result.get(0);
        assertEquals("OA考勤助手", vo.getAgentName());
        assertEquals("查一下我的考勤", vo.getPreview());
        assertFalse(vo.isStale());
    }

    /** 会话底层状态已经查不到内容（比如被清理）时，不能让整个项目详情列表跟着炸，标 stale 兜底展示。 */
    @Test
    void listSessions_shouldMarkStale_whenSessionSummaryMissing() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "退款专项"));
        AiProjectSession link = new AiProjectSession();
        link.setProjectId(1L);
        link.setAgentCode("oa-assistant");
        link.setSessionId("gone");
        when(projectSessionMapper.selectList(any())).thenReturn(List.of(link));
        when(agentMapper.selectList(any())).thenReturn(List.of());
        when(chatHistoryService.getSessionSummary(anyString(), anyString())).thenReturn(Optional.empty());

        List<ProjectSessionVO> result = service.listSessions(1L);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isStale());
    }
}
