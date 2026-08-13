package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.infra.config.properties.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用级配置（强类型绑定 {@code customer-work.*}）。
 *
 * <p>统一收口模型、会话、Agent、记忆、规划、RAG、上下文压缩、Skill、MCP、可观测、
 * 人工确认等所有可调能力，便于在不同环境通过 {@code application-*.yml} 或环境变量覆盖，
 * 而无需改动业务代码。每个能力均为"配置开关 + 可替换实现"。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@ConfigurationProperties(prefix = "customer-work")
public class CustomerWorkProperties {

    /** 模型层配置（对接百炼 / DashScope 通义千问）。 */
    private final ModelProperties model = new ModelProperties();

    /** 会话持久化配置。 */
    private final SessionProperties session = new SessionProperties();

    /** Agent 运行时配置。 */
    private final AgentProperties agent = new AgentProperties();

    /** 长期记忆配置（跨会话、多租户隔离）。 */
    private final MemoryProperties memory = new MemoryProperties();

    /** 任务规划配置（PlanNotebook）。 */
    private final PlanProperties plan = new PlanProperties();

    /** RAG 知识检索配置。 */
    private final RagProperties rag = new RagProperties();

    /** 智能上下文压缩配置（AutoContext）。 */
    private final ContextProperties context = new ContextProperties();

    /** Skill 技能库配置。 */
    private final SkillProperties skill = new SkillProperties();

    /** MCP 接入配置。 */
    private final McpProperties mcp = new McpProperties();

    /** 可观测性配置。 */
    private final ObservabilityProperties observability = new ObservabilityProperties();

    /** Human-in-the-Loop 人工确认配置。 */
    private final HumanApprovalProperties humanApproval = new HumanApprovalProperties();

    /** 三层记忆体系第三层：事实日志（只追加、可审计）。 */
    private final FactLogProperties factLog = new FactLogProperties();

    /** 接入层安全：API Key 鉴权 + 限流。 */
    private final SecurityProperties security = new SecurityProperties();

    /** Higress AI 网关接入。 */
    private final HigressProperties higress = new HigressProperties();

    /** 多 Agent 编排。 */
    private final MultiAgentProperties multiAgent = new MultiAgentProperties();

    /** 运行时与调度（优雅停机、定时维护）。 */
    private final RuntimeProperties runtime = new RuntimeProperties();

    /** 中断恢复。 */
    private final InterruptProperties interrupt = new InterruptProperties();

    /** 交互协议（AG-UI / TTS）。 */
    private final ProtocolProperties protocol = new ProtocolProperties();

    /** Nacos 接入（配置中心：系统提示词集中管理 + 热更新）。 */
    private final NacosProperties nacos = new NacosProperties();

    /** Hook 扩展能力（延迟埋点 / 出站脱敏 / 合规审计 / 自我纠错）。 */
    private final HooksProperties hooks = new HooksProperties();

    /** AgentScope 2.0 Harness 能力（权限系统 / Plan Mode / 上下文压缩 / 工作区沙箱 / 子智能体）。 */
    private final HarnessProperties harness = new HarnessProperties();

    /** 流式（SSE）传输配置：空闲超时等连接治理。 */
    private final StreamProperties stream = new StreamProperties();

    /** 多轮槽位收集（表单信息采集）存储配置。 */
    private final SlotFillingProperties slotFilling = new SlotFillingProperties();

    /** 基于 AgentScope 的定时任务调度配置（XXL-JOB 接入）。 */
    private final SchedulerProperties scheduler = new SchedulerProperties();

    /** 敏感词"一次拦截"过滤（入站/出站高性能词表拦截，智能路由中控第一块）。 */
    private final SensitiveWordProperties sensitiveWord = new SensitiveWordProperties();

    /** 会话总结建议（转人工时给接手坐席的结构化摘要，智能路由中控第二块之一）。 */
    private final AssistProperties assist = new AssistProperties();

    /** 工单智能分配（LLM 分类 + 打分器 + HITL 推荐，智能路由中控第二块之二）。 */
    private final RoutingProperties routing = new RoutingProperties();

    /** 分布式锁（跨实例互斥场景，如后台管理并发写操作互斥），基于 Redisson。 */
    private final DistributedLockProperties distributedLock = new DistributedLockProperties();

    /** 多租户隔离（tenant_id 行级过滤），SaaS 部署开启。 */
    private final TenantProperties tenant = new TenantProperties();

    /** 水平扩展：把进程内的计数与串行锁换成跨实例共享实现。 */
    private final DistributedProperties distributed = new DistributedProperties();

    /** 租户 token 配额（成本治理的硬上限）。 */
    private final QuotaProperties quota = new QuotaProperties();

    /** 对话阶段状态机存储配置。 */
    private final DialogProperties dialog = new DialogProperties();

    /** 合成监控（主动探活）配置。 */
    private final SyntheticMonitorProperties syntheticMonitor = new SyntheticMonitorProperties();

    /** 人机切换工单配置。 */
    private final HumanHandoffProperties humanHandoff = new HumanHandoffProperties();

    /** 用户反馈（消息级点赞/点踩）存储配置。 */
    private final FeedbackProperties feedback = new FeedbackProperties();

    /** 评测（意图/回复质量）运行记录与 Judge 配置。 */
    private final EvalProperties eval = new EvalProperties();

    /** badcase 回流（负反馈/质检失败 → 人工筛选 → 知识库/评测用例）配置。 */
    private final BadcaseProperties badcase = new BadcaseProperties();

    /** 语义缓存（问题向量相似即复用上次答案）配置；默认关闭，开启前先读其类注释的安全约束。 */
    private final SemanticCacheProperties semanticCache = new SemanticCacheProperties();

    /** 提示词版本追踪配置（效果归因的底座：指标掉了是不是提示词改的）。 */
    private final PromptVersionProperties promptVersion = new PromptVersionProperties();

    /** 会话级满意度（CSAT）配置：客服行业最标准的运营指标，与消息级点赞/点踩互补。 */
    private final CsatProperties csat = new CsatProperties();

    /** 知识盲区分析配置：统计"哪些问题反复查不到知识"，直接告诉运营该补什么。 */
    private final KnowledgeGapProperties knowledgeGap = new KnowledgeGapProperties();

    /** 死信队列配置：工具调用/通知发送失败后的兜底重投，量上来之后决定会不会丢单。 */
    private final DeadLetterProperties deadLetter = new DeadLetterProperties();

    /** 数据字典（少量枚举型键值数据，免建表）存储配置。 */
    private final DictProperties dict = new DictProperties();

    /** 智能客服工单配置（存储 + 转人工关键词 + SLA 阈值与自动流转）。 */
    private final TicketProperties ticket = new TicketProperties();

    /** 终端用户账户鉴权配置（存储 + JWT 参数）。 */
    private final UserAuthProperties userAuth = new UserAuthProperties();

    /** 聊天日志（会话/工单消息留痕）存储配置。 */
    private final ChatLogProperties chatLog = new ChatLogProperties();

    /** 智能体调用分段耗时统计（采集开关 + 存储模式）。 */
    private final CallLogProperties callLog = new CallLogProperties();

    /** 业务工具后端存储配置（订单/商品/售后/会员/投诉/知识库六域）。 */
    private final ToolBackendProperties toolBackend = new ToolBackendProperties();

    /** 坐席访问凭证（HMAC 令牌）配置。 */
    private final AgentAccessProperties agentAccess = new AgentAccessProperties();
}
