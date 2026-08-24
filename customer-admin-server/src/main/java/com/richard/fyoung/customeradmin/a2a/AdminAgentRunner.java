package com.richard.fyoung.customeradmin.a2a;

import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContextThreadLocalAccessor;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextThreadLocalAccessor;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 把后台管理端的动态智能体接到 A2A 服务端的执行器上。
 *
 * <p>用 {@code AgentRunner} 而不是 {@code AgentScopeA2aServer.builder(ReActAgent.Builder)}：后者要求
 * 交出一个 {@link ReActAgent.Builder}，而本项目的智能体是按 {@code ai_agent} 表现场装配出来的成品
 * （还可能被升级成 {@link HarnessAgent}），手里根本没有 Builder。{@code AgentRunner} 只要求"给消息、
 * 还事件流"，正好能包住既有的装配与缓存链路。</p>
 *
 * <p>每次请求经 {@link AgentInstanceCache#getOrBuild} 取实例，与后台对话链路共用同一份缓存——
 * 意味着改了智能体配置后，A2A 侧与页面侧同时生效，不会出现两边行为不一致。</p>
 * @author owlzhangfq@gmail.com
 */
public class AdminAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAgentRunner.class);

    private final AgentInstanceCache agentInstanceCache;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final String agentCode;
    private final String description;
    private final String tenantId;
    private final String subjectId;

    public AdminAgentRunner(AgentInstanceCache agentInstanceCache,
                            AdminAgentInstanceFactory agentInstanceFactory,
                            String agentCode, String description, String tenantId, String tokenFingerprint) {
        this.agentInstanceCache = agentInstanceCache;
        this.agentInstanceFactory = agentInstanceFactory;
        this.agentCode = agentCode;
        this.description = description;
        this.tenantId = tenantId;
        this.subjectId = "a2a:" + tokenFingerprint;
    }

    @Override
    public String getAgentName() {
        return agentCode;
    }

    @Override
    public String getAgentDescription() {
        return description;
    }

    @Override
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        // defer：装配失败（模型配置缺失、MCP 握手超时等）是同步抛异常而非发错误信号，
        // 不包一层的话异常会直接穿透 A2A 的请求处理链，客户端拿到的是连接中断而不是协议错误响应
        return Flux.defer(() -> {
            String sessionId = resolveSessionId(options);
            AgentInvocationIdentity identity = new AgentInvocationIdentity(
                tenantId, QuotaSubjectType.API_KEY, subjectId, true)
                .forInvocation(AgentInvocationIdentity.CHANNEL_A2A, sessionId, agentCode);
            Flux<Event> body = TenantContext.callWith(tenantId,
                () -> AgentInvocationIdentityContext.callWith(identity, () -> {
                    Agent agent = agentInstanceCache.getOrBuild(agentCode);
                    RuntimeContext ctx = agentInstanceFactory.contextFor(agentCode, sessionId);
                    log.info("[a2a] agent invoke: agentCode={} sessionId={} taskId={}",
                        agentCode, ctx.getSessionId(), options == null ? null : options.getTaskId());
                    return streamEvents(agent, requestMessages, ctx);
                }));
            return body.contextWrite(context -> context
                .put(TenantContextThreadLocalAccessor.KEY, tenantId)
                .put(AgentInvocationIdentityContextThreadLocalAccessor.KEY, identity));
        });
    }

    @Override
    public void stop(String taskId) {
        // 框架侧取消 A2A 任务的语义是"停止这次请求的执行"，而本项目的中断能力是按会话维度做的
        // （见 ChatService#interrupt），两者对不上。留空并记日志而不是抛异常：抛异常会让客户端的
        // 正常取消请求变成协议错误，而实际后果只是这一次请求继续跑完，不影响正确性。
        log.info("[a2a] stop requested but not supported, taskId={} agentCode={}", taskId, agentCode);
    }

    /**
     * A2A 的会话标识落到本项目的 sessionId 上：客户端传了就用它（多轮对话能接上上下文），
     * 没传则退回智能体编码（等价于"默认会话"，与后台对话链路的兜底一致）。
     */
    private String resolveSessionId(AgentRequestOptions options) {
        if (options != null && StringUtils.hasText(options.getSessionId())) {
            return options.getSessionId();
        }
        return agentCode;
    }

    /**
     * {@code stream(List, StreamOptions, RuntimeContext)} 只声明在 {@link ReActAgent}/{@link HarnessAgent}
     * 上而不在 {@code Agent} 接口上，因此这里按运行时类型分派。
     *
     * <p><b>为什么这里还在用已标记 {@code forRemoval} 的 {@code stream(...)}</b>（对话链路的
     * {@code ChatService} 已迁到 {@code streamEvents(...)}，本处刻意没跟）：{@link AgentRunner#stream}
     * 的返回类型被框架写死为 {@code Flux<}{@link Event}{@code >}，就是那个废弃类型本身；框架自带的参考
     * 实现 {@code BaseReActAgentRunner} 也仍在调 {@code agent.stream(msgs)}——A2A 这一层框架自己都没迁。
     * 若本方法改用 {@code streamEvents(...)}，拿到 {@code AgentEvent} 后还得手工 {@code new Event(...)}
     * 喂回接口，废弃 API 的暴露面一点没减少，反而要自行复刻 {@code AgentScopeAgentExecutor} 依赖的
     * {@code isLast}/{@code messageId} 去重语义（它靠这两个字段判重与拼装回包），平白引入一层易错的
     * 翻译。等框架把 A2A 层迁到细粒度事件后再跟进。</p>
     */
    private Flux<Event> streamEvents(Agent agent, List<Msg> msgs, RuntimeContext ctx) {
        StreamOptions options = StreamOptions.builder()
            // A2A 客户端要的是可交付的回答与工具产出，思考过程增量不属于协议内容，故不订阅 REASONING 细节
            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
            .incremental(true)
            .build();
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent.stream(msgs, options, ctx);
        }
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.stream(msgs, options, ctx);
        }
        return Flux.error(new IllegalStateException("unsupported agent runtime type: " + agent.getClass()));
    }
}
