package com.richard.fyoung.customeradmin.aiconfig.agent.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentVO;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentMcp;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSkill;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentSubAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentSubAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcp;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.skill.entity.AiSkill;
import com.richard.fyoung.customeradmin.aiconfig.skill.mapper.AiSkillMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiAgentSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.entity.AiSystemTool;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiAgentSystemToolMapper;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.mapper.AiSystemToolMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentService} 单测：字段校验、关联表整体替换事务、生命周期启停、菜单版本联动、系统工具关联。
 * @author owlzhangfq@gmail.com
 */
class AgentServiceTest {

    private AiAgentMapper agentMapper;
    private AiAgentMcpMapper agentMcpMapper;
    private AiAgentSkillMapper agentSkillMapper;
    private AiAgentSubAgentMapper agentSubAgentMapper;
    private AiModelConfigMapper modelConfigMapper;
    private AiMcpMapper mcpMapper;
    private AiSkillMapper skillMapper;
    private AiAgentSystemToolMapper agentSystemToolMapper;
    private AiSystemToolMapper systemToolMapper;
    private MenuVersionHolder menuVersionHolder;
    private AgentService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentMcp.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentSkill.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentSubAgent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentSystemTool.class);
    }

    @BeforeEach
    void setUp() {
        agentMapper = mock(AiAgentMapper.class);
        agentMcpMapper = mock(AiAgentMcpMapper.class);
        agentSkillMapper = mock(AiAgentSkillMapper.class);
        agentSubAgentMapper = mock(AiAgentSubAgentMapper.class);
        modelConfigMapper = mock(AiModelConfigMapper.class);
        mcpMapper = mock(AiMcpMapper.class);
        skillMapper = mock(AiSkillMapper.class);
        agentSystemToolMapper = mock(AiAgentSystemToolMapper.class);
        systemToolMapper = mock(AiSystemToolMapper.class);
        menuVersionHolder = new MenuVersionHolder();
        AgentInstanceCache agentInstanceCache = mock(AgentInstanceCache.class);
        service = new AgentService(agentMapper, agentMcpMapper, agentSkillMapper, agentSubAgentMapper,
            modelConfigMapper, mcpMapper, skillMapper, agentSystemToolMapper, systemToolMapper,
            menuVersionHolder, agentInstanceCache);

        when(modelConfigMapper.selectById(1L)).thenReturn(new AiModelConfig());
    }

    private AgentSaveRequest validRequest() {
        return new AgentSaveRequest("客服助手", "customer-helper", 1L, List.of(10L), List.of(20L), List.of(30L),
            "你是客服助手", List.of("chat", "vibecoding"), "robot", 1,
            null, null, null, null, null, null);
    }

    /** 只带 5 个高级参数的请求（能力固定 chat），供取值范围校验用例复用。 */
    private AgentSaveRequest requestWithParams(Integer maxIters, Integer toolTimeoutSeconds, Integer toolMaxAttempts,
                                               Integer compressTriggerMsgs, Integer compressKeepMsgs) {
        return new AgentSaveRequest("客服助手", "customer-helper", 1L, null, null, null,
            null, List.of("chat"), null, 1,
            null, maxIters, toolTimeoutSeconds, toolMaxAttempts, compressTriggerMsgs, compressKeepMsgs);
    }

    /** 只带 capabilities + subAgentIds 的请求，供子智能体校验用例复用。 */
    private AgentSaveRequest requestWithSubAgents(List<String> capabilities, List<Long> subAgentIds) {
        return new AgentSaveRequest("客服助手", "customer-helper", 1L, null, null, null,
            null, capabilities, null, 1,
            subAgentIds, null, null, null, null, null);
    }

    @Test
    void create_shouldRejectInvalidAgentCode() {
        AgentSaveRequest request = new AgentSaveRequest("客服助手", "Customer_Helper", 1L, null, null, null,
            null, null, null, 1, null, null, null, null, null, null);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void create_shouldRejectUnknownModelId() {
        when(modelConfigMapper.selectById(999L)).thenReturn(null);
        AgentSaveRequest request = new AgentSaveRequest("客服助手", "customer-helper", 999L, null, null, null,
            null, null, null, 1, null, null, null, null, null, null);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void create_shouldRejectInvalidMcpIds() {
        when(mcpMapper.selectBatchIds(List.of(10L))).thenReturn(List.of()); // 只有 0 条命中，说明 10L 不存在
        AgentSaveRequest request = new AgentSaveRequest("客服助手", "customer-helper", 1L, List.of(10L), null, null,
            null, null, null, 1, null, null, null, null, null, null);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void create_shouldRejectInvalidSystemToolIds() {
        when(systemToolMapper.selectBatchIds(List.of(30L))).thenReturn(List.of()); // 0 条命中，说明 30L 不存在
        AgentSaveRequest request = new AgentSaveRequest("客服助手", "customer-helper", 1L, null, null, List.of(30L),
            null, null, null, 1, null, null, null, null, null, null);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void create_shouldRejectInvalidCapability() {
        AgentSaveRequest request = new AgentSaveRequest("客服助手", "customer-helper", 1L, null, null, null,
            null, List.of("not-a-real-capability"), null, 1, null, null, null, null, null, null);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void create_shouldInsertRelationRows_andBumpMenuVersion() {
        when(mcpMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(new AiMcp()));
        when(skillMapper.selectBatchIds(List.of(20L))).thenReturn(List.of(new AiSkill()));
        when(systemToolMapper.selectBatchIds(List.of(30L))).thenReturn(List.of(new AiSystemTool()));

        long versionBefore = menuVersionHolder.current();
        service.create(validRequest());

        verify(agentMapper).insert(any(AiAgent.class));
        verify(agentMcpMapper).insert(any(AiAgentMcp.class));
        verify(agentSkillMapper).insert(any(AiAgentSkill.class));
        verify(agentSystemToolMapper).insert(any(AiAgentSystemTool.class));
        assertEquals(versionBefore + 1, menuVersionHolder.current());
    }

    @Test
    void update_shouldReplaceRelations_deleteThenInsert() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        when(agentMapper.selectById(1L)).thenReturn(existing);
        when(mcpMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(new AiMcp()));
        when(skillMapper.selectBatchIds(List.of(20L))).thenReturn(List.of(new AiSkill()));
        when(systemToolMapper.selectBatchIds(List.of(30L))).thenReturn(List.of(new AiSystemTool()));

        service.update(1L, validRequest());

        verify(agentMcpMapper).delete(any());
        verify(agentMcpMapper).insert(any(AiAgentMcp.class));
        verify(agentSkillMapper).delete(any());
        verify(agentSkillMapper).insert(any(AiAgentSkill.class));
        verify(agentSystemToolMapper).delete(any());
        verify(agentSystemToolMapper).insert(any(AiAgentSystemTool.class));
    }

    @Test
    void delete_shouldRemoveAgentAndRelations_andBumpMenuVersion() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        when(agentMapper.selectById(1L)).thenReturn(existing);

        long versionBefore = menuVersionHolder.current();
        service.delete(1L);

        verify(agentMapper).deleteById(1L);
        verify(agentMcpMapper).delete(any());
        verify(agentSkillMapper).delete(any());
        verify(agentSystemToolMapper).delete(any());
        assertEquals(versionBefore + 1, menuVersionHolder.current());
    }

    @Test
    void updateStatus_shouldBumpMenuVersion() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        when(agentMapper.selectById(1L)).thenReturn(existing);

        long versionBefore = menuVersionHolder.current();
        service.updateStatus(1L, 0);

        ArgumentCaptor<AiAgent> captor = ArgumentCaptor.forClass(AiAgent.class);
        verify(agentMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
        assertEquals(versionBefore + 1, menuVersionHolder.current());
    }

    @Test
    void get_shouldResolveModelNameAndRelationIds() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        existing.setAgentName("客服助手");
        existing.setModelId(1L);
        existing.setCapabilities("chat,vibecoding");
        when(agentMapper.selectById(1L)).thenReturn(existing);
        AiModelConfig model = new AiModelConfig();
        model.setModelName("gpt-4o");
        when(modelConfigMapper.selectById(1L)).thenReturn(model);
        AiAgentMcp relation = new AiAgentMcp();
        relation.setMcpId(10L);
        when(agentMcpMapper.selectList(any())).thenReturn(List.of(relation));
        AiAgentSystemTool toolRelation = new AiAgentSystemTool();
        toolRelation.setSystemToolId(30L);
        when(agentSystemToolMapper.selectList(any())).thenReturn(List.of(toolRelation));

        AgentVO vo = service.get(1L);

        assertEquals("gpt-4o", vo.getModelName());
        assertEquals(List.of(10L), vo.getMcpIds());
        assertEquals(List.of(30L), vo.getSystemToolIds());
        assertEquals(List.of("chat", "vibecoding"), vo.getCapabilities());
    }

    @Test
    void get_shouldRejectUnknownId() {
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.get(999L));
    }

    // ---- 新能力编码白名单 ----

    @Test
    void create_shouldAcceptNewCapabilityCodes() {
        // subagent 能力单独用例覆盖（勾选后必须选子智能体），此处覆盖其余新能力编码
        AgentSaveRequest request = new AgentSaveRequest("客服助手", "customer-helper", 1L, null, null, null,
            null, List.of("chat", "plan", "tasklist", "skill-learning", "dynamic-subagent"), null, 1,
            null, null, null, null, null, null);

        service.create(request);

        verify(agentMapper).insert(any(AiAgent.class));
    }

    @Test
    void create_shouldRejectSubagentCapabilityWithoutSubAgentIds() {
        // 勾选 subagent 能力但未选任何子智能体：空转配置，直接拒绝
        assertThrows(BizException.class,
            () -> service.create(requestWithSubAgents(List.of("chat", "subagent"), null)));
    }

    // ---- 高级参数取值范围校验 ----

    @Test
    void create_shouldRejectMaxItersOutOfRange() {
        assertThrows(BizException.class, () -> service.create(requestWithParams(0, null, null, null, null)));
        assertThrows(BizException.class, () -> service.create(requestWithParams(101, null, null, null, null)));
    }

    @Test
    void create_shouldRejectToolTimeoutSecondsOutOfRange() {
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, 0, null, null, null)));
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, 3601, null, null, null)));
    }

    @Test
    void create_shouldRejectToolMaxAttemptsOutOfRange() {
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, 0, null, null)));
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, 11, null, null)));
    }

    @Test
    void create_shouldRejectCompressTriggerMsgsOutOfRange() {
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, null, 1, null)));
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, null, 1001, null)));
    }

    @Test
    void create_shouldRejectCompressKeepMsgsOutOfRange() {
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, null, null, -1)));
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, null, null, 501)));
    }

    @Test
    void create_shouldRejectKeepMsgsNotLessThanTriggerMsgs() {
        // 两者都填时必须 keep < trigger
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, null, 20, 20)));
        assertThrows(BizException.class, () -> service.create(requestWithParams(null, null, null, 20, 30)));
    }

    @Test
    void create_shouldAcceptValidAdvancedParams_andPersistThem() {
        service.create(requestWithParams(50, 600, 3, 100, 10));

        ArgumentCaptor<AiAgent> captor = ArgumentCaptor.forClass(AiAgent.class);
        verify(agentMapper).insert(captor.capture());
        AiAgent saved = captor.getValue();
        assertEquals(50, saved.getMaxIters());
        assertEquals(600, saved.getToolTimeoutSeconds());
        assertEquals(3, saved.getToolMaxAttempts());
        assertEquals(100, saved.getCompressTriggerMsgs());
        assertEquals(10, saved.getCompressKeepMsgs());
    }

    // ---- 子智能体校验与关联维护 ----

    @Test
    void create_shouldRejectSubAgentIdsWithoutSubagentCapability() {
        when(agentMapper.selectBatchIds(List.of(30L))).thenReturn(List.of(new AiAgent()));

        assertThrows(BizException.class,
            () -> service.create(requestWithSubAgents(List.of("chat"), List.of(30L))));
    }

    @Test
    void create_shouldRejectInvalidSubAgentIds() {
        when(agentMapper.selectBatchIds(List.of(30L))).thenReturn(List.of()); // 0 条命中，说明 30L 不存在

        assertThrows(BizException.class,
            () -> service.create(requestWithSubAgents(List.of("chat", "subagent"), List.of(30L))));
    }

    @Test
    void update_shouldRejectSelfReferencedSubAgentIds() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        when(agentMapper.selectById(1L)).thenReturn(existing);
        when(agentMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(existing));

        assertThrows(BizException.class,
            () -> service.update(1L, requestWithSubAgents(List.of("chat", "subagent"), List.of(1L))));
    }

    @Test
    void update_shouldReplaceSubAgentRelations_deleteThenInsert() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        when(agentMapper.selectById(1L)).thenReturn(existing);
        when(agentMapper.selectBatchIds(List.of(30L))).thenReturn(List.of(new AiAgent()));

        service.update(1L, requestWithSubAgents(List.of("chat", "subagent"), List.of(30L)));

        verify(agentSubAgentMapper).delete(any());
        ArgumentCaptor<AiAgentSubAgent> captor = ArgumentCaptor.forClass(AiAgentSubAgent.class);
        verify(agentSubAgentMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getAgentId());
        assertEquals(30L, captor.getValue().getSubAgentId());
    }

    @Test
    void delete_shouldRemoveSubAgentRelations() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        when(agentMapper.selectById(1L)).thenReturn(existing);

        service.delete(1L);

        verify(agentSubAgentMapper).delete(any());
    }

    @Test
    void get_shouldReturnSubAgentIdsAndAdvancedParams_roundtrip() {
        AiAgent existing = new AiAgent();
        existing.setId(1L);
        existing.setModelId(1L);
        existing.setCapabilities("chat,subagent");
        existing.setMaxIters(50);
        existing.setToolTimeoutSeconds(600);
        existing.setToolMaxAttempts(3);
        existing.setCompressTriggerMsgs(100);
        existing.setCompressKeepMsgs(10);
        when(agentMapper.selectById(1L)).thenReturn(existing);
        AiAgentSubAgent relation = new AiAgentSubAgent();
        relation.setSubAgentId(30L);
        when(agentSubAgentMapper.selectList(any())).thenReturn(List.of(relation));

        AgentVO vo = service.get(1L);

        assertEquals(List.of(30L), vo.getSubAgentIds());
        assertEquals(50, vo.getMaxIters());
        assertEquals(600, vo.getToolTimeoutSeconds());
        assertEquals(3, vo.getToolMaxAttempts());
        assertEquals(100, vo.getCompressTriggerMsgs());
        assertEquals(10, vo.getCompressKeepMsgs());
        assertEquals(List.of("chat", "subagent"), vo.getCapabilities());
    }
}
