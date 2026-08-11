package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private AgentCallMetaFactory factory;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        agentMapper = mock(AiAgentMapper.class);
        factory = new AgentCallMetaFactory(agentMapper);
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
}
