package com.richard.fyoung.customeradmin.aiconfig.systemtool.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SystemToolService} 单测：分页查询映射、编辑落库、编辑后失效引用该工具的智能体缓存。
 * @author owlzhangfq@gmail.com
 */
class SystemToolServiceTest {

    private AiSystemToolMapper systemToolMapper;
    private AiAgentSystemToolMapper agentSystemToolMapper;
    private AiAgentMapper agentMapper;
    private AgentInstanceCache agentInstanceCache;
    private SystemToolService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiSystemTool.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentSystemTool.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        systemToolMapper = mock(AiSystemToolMapper.class);
        agentSystemToolMapper = mock(AiAgentSystemToolMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        agentInstanceCache = mock(AgentInstanceCache.class);
        service = new SystemToolService(systemToolMapper, agentSystemToolMapper, agentMapper, agentInstanceCache);
    }

    @Test
    void page_shouldMapEntityToVo() {
        AiSystemTool tool = new AiSystemTool();
        tool.setId(1L);
        tool.setToolCode("httpclient");
        tool.setToolName("HTTP请求工具");
        tool.setEnabled(1);
        Page<AiSystemTool> page = new Page<>(1, 10);
        page.setRecords(List.of(tool));
        page.setTotal(1);
        when(systemToolMapper.selectPage(any(), any())).thenReturn((IPage) page);

        PageResult<SystemToolVO> result = service.page(new PageQuery());

        assertEquals(1, result.getList().size());
        assertEquals("httpclient", result.getList().get(0).getToolCode());
    }

    @Test
    void update_shouldPersist_andEvictReferencingAgents() {
        AiSystemTool tool = new AiSystemTool();
        tool.setId(1L);
        tool.setToolCode("httpclient");
        when(systemToolMapper.selectById(1L)).thenReturn(tool);
        AiAgentSystemTool relation = new AiAgentSystemTool();
        relation.setAgentId(100L);
        when(agentSystemToolMapper.selectList(any())).thenReturn(List.of(relation));
        AiAgent agent = new AiAgent();
        agent.setAgentCode("sales-assistant");
        when(agentMapper.selectBatchIds(List.of(100L))).thenReturn(List.of(agent));

        service.update(1L, new SystemToolSaveRequest("HTTP请求工具", "desc", 0, "remark"));

        verify(systemToolMapper).updateById(any(AiSystemTool.class));
        verify(agentInstanceCache).invalidateAll(List.of("sales-assistant"));
    }

    @Test
    void update_shouldNotEvict_whenNoReferencingAgents() {
        AiSystemTool tool = new AiSystemTool();
        tool.setId(1L);
        tool.setToolCode("httpclient");
        when(systemToolMapper.selectById(1L)).thenReturn(tool);
        when(agentSystemToolMapper.selectList(any())).thenReturn(List.of());

        service.update(1L, new SystemToolSaveRequest("HTTP请求工具", "desc", 1, "remark"));

        verify(systemToolMapper).updateById(any(AiSystemTool.class));
        verify(agentInstanceCache, times(0)).evictAll(any());
    }

    @Test
    void update_shouldRejectUnknownId() {
        when(systemToolMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class,
            () -> service.update(999L, new SystemToolSaveRequest("x", null, 1, null)));
    }
}
