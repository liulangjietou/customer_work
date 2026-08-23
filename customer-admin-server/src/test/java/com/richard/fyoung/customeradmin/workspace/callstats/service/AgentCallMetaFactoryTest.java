package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentCallMetaFactory} 单测：无 Sa-Token 上下文时用户名防御兜底（留 null），agentName 取自
 * ai_agent（查不到/异常回落 agentCode），requestId 生成、sessionType/question 原样透传。
 * @author owlzhangfq@gmail.com
 */
class AgentCallMetaFactoryTest {

    private AiAgentMapper agentMapper;
    private ModelConfigAccess modelConfigAccess;
    private AgentCallMetaFactory factory;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        agentMapper = mock(AiAgentMapper.class);
        modelConfigAccess = mock(ModelConfigAccess.class);
        factory = new AgentCallMetaFactory(agentMapper, modelConfigAccess);
    }

    @Test
    void build_shouldResolveAgentName_andLeaveUsernameNullWithoutLoginContext() {
        AiAgent agent = new AiAgent();
        agent.setAgentCode("coder");
        agent.setAgentName("编码助手");
        when(agentMapper.selectOne(any())).thenReturn(agent);

        AgentCallMeta meta = factory.build("coder", AgentCallSessionType.VIBE_CODING, "写个脚本");

        assertNotNull(meta.requestId(), "requestId 应生成");
        assertNull(meta.username(), "无登录上下文用户名兜底为 null");
        assertEquals("coder", meta.agentCode());
        assertEquals("编码助手", meta.agentName());
        assertEquals(AgentCallSessionType.VIBE_CODING, meta.sessionType());
        assertEquals("写个脚本", meta.question());
        assertFalse(meta.lineage().versionBinding().promptVersion().isEmpty());
        assertFalse(meta.lineage().versionBinding().agentVersion().isEmpty());
    }

    @Test
    void build_shouldFallbackAgentNameToCode_whenAgentMissing() {
        when(agentMapper.selectOne(any())).thenReturn(null);
        AgentCallMeta meta = factory.build("coder", AgentCallSessionType.CHAT, "你好");
        assertEquals("coder", meta.agentName());
    }

    @Test
    void build_shouldFallbackAgentNameToCode_whenMapperThrows() {
        when(agentMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        AgentCallMeta meta = factory.build("coder", AgentCallSessionType.CHAT, "你好");
        assertEquals("coder", meta.agentName(), "查询异常时 agentName 防御性回落 agentCode");
    }

    @Test
    void build_shouldCaptureModelLineage_withoutCredentialMaterial() {
        AiAgent agent = new AiAgent();
        agent.setId(7L);
        agent.setAgentCode("coder");
        agent.setModelId(11L);
        agent.setSystemPrompt("只回答代码问题");
        when(agentMapper.selectOne(any())).thenReturn(agent);

        AiModelConfig model = new AiModelConfig();
        model.setId(11L);
        model.setAssetId(3L);
        model.setDeploymentCode("prod-coder");
        model.setProvider("openai");
        model.setProtocolAdapter("openai-compatible");
        model.setBaseUrl("https://model.example/v1");
        model.setModel("coder-large");
        model.setEndpointRevision(2);
        model.setLifecycleStatus("ACTIVE");
        model.setApiKey("cipher-v1");
        model.setSecretRefId(21L);
        when(modelConfigAccess.findVisibleAnyStateById(11L)).thenReturn(model);

        AgentCallMeta first = factory.build("coder", AgentCallSessionType.CHAT, "review");
        model.setApiKey("cipher-v2");
        model.setSecretRefId(22L);
        AgentCallMeta rotated = factory.build("coder", AgentCallSessionType.CHAT, "review");

        String firstVersion = first.lineage().versionBinding().modelVersion();
        assertFalse(firstVersion.isEmpty());
        assertEquals(firstVersion, rotated.lineage().versionBinding().modelVersion(),
            "凭据轮换不得改变模型制品指纹");

        model.setEndpointRevision(3);
        AgentCallMeta changedEndpoint = factory.build("coder", AgentCallSessionType.CHAT, "review");
        assertNotEquals(firstVersion, changedEndpoint.lineage().versionBinding().modelVersion(),
            "推理端点版本变化必须产生新指纹");
    }
}
