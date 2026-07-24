package com.richard.fyoung.customeradmin.workspace.callstats.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customerwork.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.calllog.AgentCallSessionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 构建一次调用的 {@link AgentCallMeta}——必须在<b>请求线程的同步段</b>调用（用户名取自 Sa-Token 的
 * ThreadLocal，SSE 的 reactor 回调线程里已拿不到登录上下文，与审计埋点同一约束）。
 *
 * <p>requestId 生成 UUID（admin 无全链路 requestId 机制）；username 取当前登录用户账号（未登录/无上下文
 * 兜底 null，中间件会回落 ctx.userId）；agentName 取 {@code ai_agent.agent_name}（查不到回落 agentCode）；
 * sessionType 由调用方按渠道传入（对话=CHAT，VibeCoding=VIBE_CODING）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AgentCallMetaFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentCallMetaFactory.class);

    private final AiAgentMapper agentMapper;

    public AgentCallMetaFactory(AiAgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    /** 构建调用元数据。{@code question} 为用户原始输入（VibeCoding 传原始需求而非注入路径指引后的富文本）。 */
    public AgentCallMeta build(String agentCode, AgentCallSessionType sessionType, String question) {
        return new AgentCallMeta(UUID.randomUUID().toString(), resolveUsername(), agentCode,
            resolveAgentName(agentCode), sessionType, question);
    }

    /** 当前登录用户账号；未登录/无 Sa-Token 上下文（如单测/开放 API）时返回 null，不阻断对话。 */
    private String resolveUsername() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getTokenSession().getString("username");
            }
        } catch (Exception e) {
            log.error("resolve call meta username failed, code={}", "CALLSTATS-META-USER-FAIL", e);
        }
        return null;
    }

    /** 智能体展示名（ai_agent.agent_name）；查不到或异常回落 agentCode。 */
    private String resolveAgentName(String agentCode) {
        try {
            AiAgent agent = agentMapper.selectOne(
                new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, agentCode));
            if (agent != null && StringUtils.hasText(agent.getAgentName())) {
                return agent.getAgentName();
            }
        } catch (Exception e) {
            log.error("resolve call meta agent name failed, code={}, agentCode={}",
                "CALLSTATS-META-AGENT-FAIL", agentCode, e);
        }
        return agentCode;
    }
}
