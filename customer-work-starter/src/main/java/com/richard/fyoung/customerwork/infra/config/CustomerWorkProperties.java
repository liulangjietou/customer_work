package com.richard.fyoung.customerwork.infra.config;

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
    private final Model model = new Model();

    /** 会话持久化配置。 */
    private final Session session = new Session();

    /** Agent 运行时配置。 */
    private final Agent agent = new Agent();

    /** 长期记忆配置（跨会话、多租户隔离）。 */
    private final Memory memory = new Memory();

    /** 任务规划配置（PlanNotebook）。 */
    private final Plan plan = new Plan();

    /** RAG 知识检索配置。 */
    private final Rag rag = new Rag();

    /** 智能上下文压缩配置（AutoContext）。 */
    private final Context context = new Context();

    /** Skill 技能库配置。 */
    private final Skill skill = new Skill();

    /** MCP 接入配置。 */
    private final Mcp mcp = new Mcp();

    /** 可观测性配置。 */
    private final Observability observability = new Observability();

    /** Human-in-the-Loop 人工确认配置。 */
    private final HumanApproval humanApproval = new HumanApproval();

    /** 三层记忆体系第三层：事实日志（只追加、可审计）。 */
    private final FactLog factLog = new FactLog();

    /** 接入层安全：API Key 鉴权 + 限流。 */
    private final Security security = new Security();

    /** Higress AI 网关接入。 */
    private final Higress higress = new Higress();

    /** 多 Agent 编排。 */
    private final MultiAgent multiAgent = new MultiAgent();

    /** 运行时与调度（优雅停机、定时维护）。 */
    private final Runtime runtime = new Runtime();

    /** 中断恢复。 */
    private final Interrupt interrupt = new Interrupt();

    /** 交互协议（AG-UI / TTS）。 */
    private final Protocol protocol = new Protocol();

    /** Nacos 接入（配置中心：系统提示词集中管理 + 热更新）。 */
    private final Nacos nacos = new Nacos();

    /** Hook 扩展能力（延迟埋点 / 出站脱敏 / 合规审计 / 自我纠错）。 */
    private final Hooks hooks = new Hooks();

    /** AgentScope 2.0 Harness 能力（权限系统 / Plan Mode / 上下文压缩 / 工作区沙箱 / 子智能体）。 */
    private final Harness harness = new Harness();

    /** 流式（SSE）传输配置：空闲超时等连接治理。 */
    private final Stream stream = new Stream();

    /** 多轮槽位收集（表单信息采集）存储配置。 */
    private final SlotFilling slotFilling = new SlotFilling();

    /** 基于 AgentScope 的定时任务调度配置（XXL-JOB 接入）。 */
    private final Scheduler scheduler = new Scheduler();

    /** 敏感词"一次拦截"过滤（入站/出站高性能词表拦截，智能路由中控第一块）。 */
    private final SensitiveWord sensitiveWord = new SensitiveWord();

    /** 会话总结建议（转人工时给接手坐席的结构化摘要，智能路由中控第二块之一）。 */
    private final Assist assist = new Assist();

    /** 工单智能分配（LLM 分类 + 打分器 + HITL 推荐，智能路由中控第二块之二）。 */
    private final Routing routing = new Routing();

    /** 分布式锁（跨实例互斥场景，如后台管理并发写操作互斥），基于 Redisson。 */
    private final DistributedLock distributedLock = new DistributedLock();

    /** 多租户隔离（tenant_id 行级过滤），SaaS 部署开启。 */
    private final Tenant tenant = new Tenant();

    /**
     * 多租户配置。默认关闭——单租户部署（升级前的既有形态）行为完全不变。
     *
     * <p>开启后 {@code TenantLineInnerInterceptor} 接管本 starter 独立 MyBatis 环境下的全部
     * SELECT/UPDATE/DELETE 改写与 INSERT 补列，且缺失租户上下文时 fail-closed 直接报错，
     * 而不是放行成全量查询——静默返回跨租户数据比报错危险得多。</p>
     */
    @Data
    public static class Tenant {

        /** 是否开启多租户行级隔离。 */
        private boolean enabled = false;

        /** 租户列名（全部业务表统一）。 */
        private String columnName = "tenant_id";

        /**
         * 不参与租户过滤的表（平台级数据 + 框架自建表）。
         *
         * <p>清单语义见 {@code docs/多租户架构设计.md}：这里的表要么内容由平台定义租户只读，
         * 要么根本没有 {@code tenant_id} 列（框架自建），漏配会让 SQL 拼出不存在的列而报错。</p>
         */
        private List<String> ignoredTables = new ArrayList<>();

        /**
         * 是否在 starter 内开启 Reactor 自动上下文传播（{@code Hooks.enableAutomaticContextPropagation()}）。
         *
         * <p>WebFlux 主链路会切到 boundedElastic 执行阻塞 JDBC，而 MyBatis 拦截器读的是 ThreadLocal——
         * 不传播就必然在跨线程边界丢租户。该 Hook 是 JVM 全局的，故只在多租户开启时才装。</p>
         */
        private boolean autoContextPropagation = true;
    }

    /** 水平扩展：把进程内的计数与串行锁换成跨实例共享实现。 */
    private final Distributed distributed = new Distributed();

    /** 租户 token 配额（成本治理的硬上限）。 */
    private final Quota quota = new Quota();

    /**
     * 租户配额配置。默认关闭——没配配额时行为与引入配额之前完全一致。
     *
     * <p>只判 token 不判金额：金额需要单价表（在 admin 库），让客服端跨库反查只为算一个
     * 实时金额并不划算；token 本就是成本的直接驱动。金额维度走 T+1 账单与预警。</p>
     */
    @Data
    public static class Quota {

        /** 是否开启配额判定。关闭时 Guard 一律放行，也不记账。 */
        private boolean enabled = false;

        /** 配额存储：{@code memory} 进程内 / {@code jdbc} 落 {@code cw_tenant_quota}。 */
        private String storeMode = "memory";
    }

    /**
     * 水平扩展配置。默认全 memory——单实例部署下进程内实现更快也更简单，
     * 多副本部署才需要切 redis，否则限流与成本配额会被放大成实例数倍。
     *
     * <p>Redis 连接复用 {@code customer-work.distributed-lock.redis.*}，不再单独配一套。</p>
     */
    @Data
    public static class Distributed {

        /** 限流与成本熔断的窗口计数实现：{@code memory} 进程内 / {@code redis} 跨实例共享。 */
        private String counterMode = "memory";

        /** Redis 计数键前缀，多套环境共用一个 Redis 时用它隔离。 */
        private String counterKeyPrefix = "cw:counter:";

        /**
         * 会话串行锁实现：{@code memory} 进程内信号量 / {@code redis} 分布式锁。
         *
         * <p>进程内锁要求网关按会话做 sticky 路由才成立；一旦同一会话可能落到不同实例，
         * 必须切 redis，否则同一会话的并发请求会同时进入模型调用，历史被交叉写坏。</p>
         */
        private String sessionLockMode = "memory";

        /** 分布式会话锁的最长等待时间（秒），等不到即按繁忙拒绝，避免请求线程无限堆积。 */
        private int sessionLockWaitSeconds = 10;

        /** 分布式会话锁的持有超时（秒），防止实例崩溃后锁永不释放。 */
        private int sessionLockLeaseSeconds = 120;
    }

    /**
     * 会话总结建议配置。默认关闭。
     *
     * <p>转人工时对整段历史做一次性 LLM 总结，产出结构化 {@code ConversationSummary} 供接手坐席快速了解上下文。
     * 失败 fail-open（降级到规则版 {@code AgentAssistService} 建议，绝不阻断转人工主链路）。按需触发的
     * {@code GET /api/customer/assist/summary} 端点不受本开关约束（显式人工请求），本开关仅控制转人工时的自动预生成。</p>
     */
    @Data
    public static class Assist {
        /** 转人工时自动预生成会话摘要（默认关，开启会产生真实模型调用费用）。 */
        private boolean summaryEnabled = false;
        /** 摘要拉取会话历史的最大条数。 */
        private int summaryHistoryLimit = 30;
        /** 单次摘要 LLM 调用超时（秒）。 */
        private long summaryTimeoutSeconds = 30;
        /** 摘要缓存的最大会话数（供坐席工作台按 sessionId 拉取预生成结果，超出按插入序淘汰最旧）。 */
        private int summaryCacheMaxSessions = 512;
    }

    /**
     * 工单智能分配配置。默认关闭。
     *
     * <p>转人工时对工单做一次性 LLM 分类（类目/技能/优先级/情绪）+ 确定性打分器给候选坐席打分，把 top-N
     * 推荐写入工单供坐席工作台展示——<b>仅推荐、不自动派单</b>，人工点选后仍复用现有 claim 流程。任一步失败
     * fail-open（退回现有拉取模型：无推荐、坐席照常手动接单），不阻断转人工。</p>
     */
    @Data
    public static class Routing {
        /** 转人工时自动做工单分类 + 坐席打分推荐（默认关；不自动派单，仅写入推荐供坐席点选）。 */
        private boolean assignEnabled = false;
        /** 坐席库存储模式：memory（进程内带演示种子，默认）| jdbc（跨实例共享）。 */
        private String seatStoreMode = "memory";
        /** 推荐坐席 top-N（默认 3）。 */
        private int topN = 3;
        /** 单次工单分类 LLM 调用超时（秒）。 */
        private long classifyTimeoutSeconds = 30;
    }

    /** 分布式锁配置：{@code DistributedLockExecutor} 底层 RedissonClient 连接的 Redis。 */
    @Data
    public static class DistributedLock {
        private final Redis redis = new Redis();

        @Data
        public static class Redis {
            private String host = "localhost";
            private int port = 6379;
            private String password = "";
        }
    }

    /**
     * 多轮槽位收集存储配置。
     *
     * <p>决定 {@code SlotFillingService} 收集进度的持久化方式，语义与 {@link HumanApproval#storeMode}
     * 一致：memory 模式下应用重启会丢失正在进行的多轮收集（用户需重新开始）；jdbc 模式持久化到数据库，
     * 重启可续填。</p>
     */
    @Data
    public static class SlotFilling {
        /** 存储模式：memory（进程内，默认）| jdbc（数据库持久化）。 */
        private String storeMode = "memory";
    }

    /** 对话阶段状态机存储配置。 */
    private final Dialog dialog = new Dialog();

    /**
     * 对话阶段状态机存储配置。
     *
     * <p>决定 {@code DialogStageService} 的会话阶段持久化方式：memory 模式下多实例部署时，
     * 请求被负载均衡到不同实例会导致阶段状态"归零"回 GREETING（动态 Prompt 状态机失效）；
     * jdbc 模式跨实例共享同一份阶段状态。</p>
     */
    @Data
    public static class Dialog {
        /** 存储模式：memory（进程内，默认，仅单实例场景适用）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
    }

    /** 合成监控（主动探活）配置。 */
    private final SyntheticMonitor syntheticMonitor = new SyntheticMonitor();

    /**
     * 合成监控（主动探活）：定时用固定探针会话打真实对话链路，在用户上报前发现故障。
     *
     * <p><b>默认关闭</b>——每次探测会真实调用模型（产生费用）。开启后建议探测间隔不宜过密。
     * 探针使用独立 {@code sessionId}（与真实会话隔离），每次探测后清理其状态。</p>
     */
    @Data
    public static class SyntheticMonitor {
        /** 是否启用主动探活（默认关闭，开启会产生真实模型调用费用）。 */
        private boolean enabled = false;
        /** 探测间隔（毫秒），默认 5 分钟。 */
        private long intervalMs = 300000;
        /** 单次探测超时（秒）。 */
        private long timeoutSeconds = 30;
        /** 探针会话 ID（独立命名空间，避免污染真实会话）。 */
        private String sessionId = "synthetic:healthcheck";
        /** 探针消息文本。 */
        private String message = "你好";
    }

    /**
     * 流式（SSE）传输配置。
     *
     * <p>缓解框架 #1741：SSE 流在下游长时间无数据时无法自行关闭，导致连接泄漏。
     * 通过空闲超时把长时间不产元素的流转成一条友好收尾消息后正常结束。</p>
     */
    @Data
    public static class Stream {
        /**
         * SSE 流空闲超时（秒）：相邻两个元素间隔超过该值即触发超时兜底收尾。
         * 默认 120；{@code <=0} 表示禁用空闲超时。
         */
        private long idleTimeoutSeconds = 120;
    }

    /** 模型层配置。生产建议用环境变量 {@code DASHSCOPE_API_KEY} 注入密钥。 */
    @Data
    public static class Model {
        /** 模型厂商：dashscope（百炼）| openai | anthropic | gemini | ollama。 */
        private String provider = "dashscope";
        private String apiKey;
        private String name = "qwen-max";
        private String baseUrl;
        private Double temperature = 0.3;
        private Integer maxTokens = 1500;
        private boolean stream = true;
        /** 采样 topP（GenerateOptions 高级）。 */
        private Double topP;
        /** 推理强度（reasoning effort）：low / medium / high（GenerateOptions 高级）。 */
        private String reasoningEffort;
        /** DashScope 联网搜索（仅 dashscope 生效）。 */
        private Boolean enableSearch;
        /** DashScope 深度思考（仅 dashscope 生效）。 */
        private Boolean enableThinking;
        /** Embedding 模型名（RAG 接百炼向量检索时使用）。 */
        private String embeddingName = "text-embedding-v3";
        /** 单次请求 token 用量告警阈值（0=关闭）；超过则可观测 Hook 打 WARN，便于成本护栏。 */
        private int tokenWarnThreshold = 0;
        /** 成本熔断配置（按时间窗口限制 token 消耗量）。 */
        private final CostControl costControl = new CostControl();
        /** 私有化兜底：主模型失败时切换到兜底模型。 */
        private final Fallback fallback = new Fallback();
        /** 模型调用重试（瞬时错误指数退避，提升高可用）。 */
        private final Retry retry = new Retry();

        @Data
        public static class Retry {
            private boolean enabled = false;
            private int maxAttempts = 2;
            private long backoffMs = 500;
        }

        @Data
        public static class Fallback {
            private boolean enabled = false;
            private String provider = "ollama";
            private String name = "qwen2.5";
            private String apiKey = "";
            private String baseUrl = "";
        }

        /** 成本熔断：按时间窗口限制 token 消耗量，超限拒绝请求防刷量打爆成本。 */
        @Data
        public static class CostControl {
            /** 是否启用成本熔断。 */
            private boolean enabled = false;
            /** 每分钟最大 token 消耗量；超过则熔断拒绝请求。 */
            private int maxTokensPerMinute = 100_000;
            /** 每小时最大 token 消耗量；超过则熔断。 */
            private int maxTokensPerHour = 1_000_000;
        }
    }

    /** 会话持久化配置：memory | json | redis | mysql。 */
    @Data
    public static class Session {
        private String mode = "memory";
        private String directory = "./data/sessions";
        /** 会话空闲超时（分钟）：超过该时间无活动的会话自动清理；<=0 禁用。 */
        private int idleTimeoutMinutes = 0;
        /** Redis 连接配置（mode=redis 时生效）。 */
        private final Redis redis = new Redis();
        /** MySQL 连接配置（mode=mysql 时生效）。 */
        private final Mysql mysql = new Mysql();

        @Data
        public static class Redis {
            private String host = "localhost";
            private int port = 6379;
            private String password = "";
            private String keyPrefix = "customer-work";
        }

        @Data
        public static class Mysql {
            private String host = "localhost";
            private int port = 3306;
            private String database = "agent_scope_customer_work";
            private String username = "root";
            private String password = "root";
            /** 完整 JDBC URL（留空则按 host/port/database 自动拼装）。 */
            private String jdbcUrl = "";
            /** 是否自动建库建表。 */
            private boolean autoCreate = true;

            /** 解析最终使用的 JDBC URL。 */
            public String resolveJdbcUrl() {
                if (jdbcUrl != null && !jdbcUrl.isBlank()) {
                    return jdbcUrl;
                }
                return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8";
            }
        }
    }

    /** Agent 运行时配置。 */
    @Data
    public static class Agent {
        private int maxIters = 10;
        /** Meta-Tool（元工具）：Agent 运行时自主启停工具组，缓解上下文窗口压力。 */
        private boolean metaToolEnabled = false;
    }

    /** 长期记忆配置。 */
    @Data
    public static class Memory {
        private boolean longTermEnabled = true;
        /** 长期记忆实现：memory（内置内存）| bailian（百炼）| mem0 | reme。 */
        private String provider = "memory";
        /** 租户解析分隔符：sessionId 形如 tenantA:conv-1 时分隔符前为租户。 */
        private String tenantDelimiter = ":";
        private int retrieveTopK = 5;
        /** 百炼长期记忆配置（provider=bailian 时生效）。 */
        private final Bailian bailian = new Bailian();
        /** Mem0 长期记忆配置（provider=mem0 时生效）。 */
        private final Mem0 mem0 = new Mem0();
        /** ReMe 长期记忆配置（provider=reme 时生效）。 */
        private final ReMe reme = new ReMe();

        @Data
        public static class Bailian {
            /** 百炼 API Key；留空则复用 model.api-key。 */
            private String apiKey = "";
            private String apiBaseUrl = "";
            /** 记忆库 ID（在百炼控制台创建）。 */
            private String memoryLibraryId = "";
            private String projectId = "";
            private int topK = 5;
        }

        @Data
        public static class Mem0 {
            private String apiKey = "";
            /** Mem0 服务地址（platform 默认官方云端点；self_hosted 填自部署地址）。 */
            private String apiBaseUrl = "https://api.mem0.ai";
            /** API 类型：platform（Mem0 云）| self_hosted（自部署）。 */
            private String apiType = "platform";
            private String agentName = "customer-work";
        }

        @Data
        public static class ReMe {
            private String apiBaseUrl = "";
        }
    }

    /** 任务规划配置。 */
    @Data
    public static class Plan {
        private boolean enabled = true;
        private int maxSubtasks = 20;
    }

    /** RAG 配置。 */
    @Data
    public static class Rag {
        /** 是否启用 RAG。 */
        private boolean enabled = true;
        /**
         * 知识库实现：memory（内置内存关键词）| simple（百炼 Embedding + 内存向量库，真实语义检索）
         * | bailian（百炼企业知识库）| dify | ragflow | haystack。
         */
        private String provider = "memory";
        /** 召回条数上限。 */
        private int topK = 3;
        /** simple 向量 RAG 配置（provider=simple 时生效）。 */
        private final Simple simple = new Simple();
        /** 百炼企业知识库配置（provider=bailian 时生效）。 */
        private final Bailian bailian = new Bailian();
        /** Dify 知识库配置（provider=dify 时生效）。 */
        private final Dify dify = new Dify();

        @Data
        public static class Dify {
            private String apiKey = "";
            private String apiBaseUrl = "";
            private String datasetId = "";
            private boolean enableRerank = true;
        }

        /** 真实 Embedding 向量 RAG：使用 model.embedding-name 与 model.api-key。 */
        @Data
        public static class Simple {
            /** 向量维度（text-embedding-v3 默认 1024）。 */
            private int dimensions = 1024;
        }

        @Data
        public static class Bailian {
            private String accessKeyId = "";
            private String accessKeySecret = "";
            private String workspaceId = "";
            /** 知识库索引 ID（在百炼控制台创建）。 */
            private String indexId = "";
            private String endpoint = "";
            private boolean enableReranking = true;
        }
    }

    /** 智能上下文压缩配置（对应 AutoContextMemory）。 */
    @Data
    public static class Context {
        /** 是否启用自动上下文压缩（长对话上下文有界）。默认关闭，开启需可用模型。 */
        private boolean compressionEnabled = false;
        /** 触发压缩的最大 token 阈值。 */
        private long maxToken = 8000;
        /** 触发压缩的消息条数阈值。 */
        private int msgThreshold = 40;
        /** 压缩时保留最近 N 条消息原文。 */
        private int lastKeep = 10;
    }

    /** Skill 技能库配置。 */
    @Data
    public static class Skill {
        /** 是否启用 Skill。 */
        private boolean enabled = true;
        /** 仓库类型：classpath（只读内置）| filesystem（可读写，支持技能自进化/写回）。 */
        private String repository = "classpath";
        /** classpath 仓库的资源目录。 */
        private String location = "skills";
        /** filesystem 仓库的磁盘目录。 */
        private String directory = "./data/skills";
        /** filesystem 仓库是否可写（支持 Agent 沉淀 / 上传新技能）。 */
        private boolean writable = true;
        /** 是否注册"运行时加载技能"工具，允许 Agent 按需自行加载技能。 */
        private boolean runtimeLoadToolEnabled = false;
        /** 是否启用代码执行技能（注册读写/Shell 工具，使技能可执行代码）。默认关闭。 */
        private boolean codeExecutionEnabled = false;
        /** 代码执行工作目录。 */
        private String codeExecutionWorkDir = "./data/skill-workspace";
    }

    /** MCP 接入配置。 */
    @Data
    public static class Mcp {
        /** 是否启用 MCP 接入（把存量 HTTP 系统零改造接成 Agent 工具）。默认关闭。 */
        private boolean enabled = false;
        /** MCP 服务列表。 */
        private List<Server> servers = new ArrayList<>();

        @Data
        public static class Server {
            private String name;
            private String url;
            /** 传输类型：sse | streamable-http。 */
            private String transport = "sse";
            /** 附加请求头（如 Authorization: Bearer xxx），接需要鉴权的远程 MCP 服务时配置。 */
            private java.util.Map<String, String> headers;
        }
    }

    /** 可观测性配置。 */
    @Data
    public static class Observability {
        /** 是否把全链路 trace 导出为 JSONL 文件（数据飞轮采集）。 */
        private boolean traceEnabled = false;
        private String traceFile = "./data/traces/agent-trace.jsonl";
        /** 是否启用框架原生链路追踪 Hook（TracerRegistry），按模型/工具/Agent 调用打 span。 */
        private boolean tracingEnabled = false;
        /**
         * 是否把 Reactor Context 中的 requestId / sessionId 同步到 SLF4J MDC，
         * 使反应式链路上任意线程的日志都能带上全链路关联字段（配合 logback pattern 的 %X{requestId}）。
         * 生产建议开启——线上故障定位的最短路径（用户报障给 requestId，日志按之聚合）。
         */
        private boolean mdcEnabled = true;
        /**
         * 是否解析上游 W3C {@code traceparent} 头，把 trace-id 关联进日志 MDC（键 traceId），
         * 使本服务日志与外部分布式链路（Jaeger/Tempo）共享同一 traceId。零外部依赖，默认开启。
         */
        private boolean traceCorrelationEnabled = true;
        /** Studio 可视化调试对接。 */
        private final Studio studio = new Studio();
        /** OpenTelemetry SDK 接入（真正采集并导出 span 到 Collector/Tempo）。 */
        private final Otel otel = new Otel();

        @Data
        public static class Studio {
            private boolean enabled = false;
            private String url = "";
            private String project = "customer-work";
            private String runName = "customer-work-run";
        }

        /**
         * OpenTelemetry SDK 配置（{@code customer-work.observability.otel.*}）。
         *
         * <p>与上面的 {@code tracingEnabled}（框架 TracerRegistry + LoggingTracer，只打日志）、
         * {@code traceCorrelationEnabled}（只解析 traceparent 做日志关联，零外部依赖）是三个不同层次：
         * 本组开启后才真正在进程内采集 span 并经 OTLP 导出，是"最后一公里"。</p>
         */
        @Data
        public static class Otel {
            /** 是否接入 OTel SDK（注册 GlobalOpenTelemetry + 挂 OtelTracingMiddleware + HTTP server span）。 */
            private boolean enabled = false;
            /** OTLP gRPC 接收端地址（Collector / Tempo）。 */
            private String endpoint = "http://localhost:4317";
            /** 资源属性 service.name，链路后台按之区分服务。 */
            private String serviceName = "customer-work";
            /** 采样比例 [0,1]：1.0 全采，生产高流量建议下调（父级已采样的链路始终跟随父级）。 */
            private double samplerRatio = 1.0d;
        }
    }

    /** Human-in-the-Loop 人工确认配置。 */
    @Data
    public static class HumanApproval {
        /** 是否启用工具级人工确认（高风险工具执行后暂停 Agent 待人工复核）。 */
        private boolean enabled = true;
        /** 受控（需人工确认）的工具名集合。 */
        private List<String> guardedTools = new ArrayList<>(List.of("submitRefund"));
        /** 审批超时（秒）：PENDING 超过该时间未决策则自动超时处理；<=0 禁用。 */
        private long timeoutSeconds = 0;
        /** 超时动作：escalate（升级转人工）| deny（自动拒绝）。 */
        private String timeoutAction = "escalate";
        /** 审批存储模式：memory（进程内，默认）| jdbc（数据库持久化）。 */
        private String storeMode = "memory";
        /** 审批放行后下游执行（如实际打款）失败的最大重试次数（含首次执行）；&lt;=1 表示不重试。 */
        private int maxExecutionRetryAttempts = 3;
    }

    /** 人机切换工单配置。 */
    private final HumanHandoff humanHandoff = new HumanHandoff();

    /**
     * 人机切换工单存储配置。
     *
     * <p>决定 {@code HandoffService} 的工单持久化方式：memory 模式下多实例部署时，坐席在实例 A
     * 接单、坐席工作台轮询落到实例 B 会查不到最新状态；jdbc 模式跨实例共享同一份工单状态。</p>
     */
    @Data
    public static class HumanHandoff {
        /** 存储模式：memory（进程内，默认，仅单实例场景适用）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
        /** SLA 告警：PENDING（无人接单）超过该秒数即告警；&lt;=0 禁用。 */
        private long slaPendingSeconds = 0;
        /** SLA 告警：CLAIMED（接单未结案）超过该秒数即告警；&lt;=0 禁用。 */
        private long slaClaimedSeconds = 0;
    }

    /** 用户反馈（消息级点赞/点踩）存储配置。 */
    private final Feedback feedback = new Feedback();

    /** 数据字典（少量枚举型键值数据，免建表）存储配置。 */
    private final Dict dict = new Dict();

    /** 智能客服工单配置（存储 + 转人工关键词 + SLA 阈值与自动流转）。 */
    private final Ticket ticket = new Ticket();

    /** 终端用户账户鉴权配置（存储 + JWT 参数）。 */
    private final UserAuth userAuth = new UserAuth();

    /** 聊天日志（会话/工单消息留痕）存储配置。 */
    private final ChatLog chatLog = new ChatLog();

    /** 智能体调用分段耗时统计（采集开关 + 存储模式）。 */
    private final CallLog callLog = new CallLog();

    /** 业务工具后端存储配置（订单/商品/售后/会员/投诉/知识库六域）。 */
    private final ToolBackend toolBackend = new ToolBackend();

    /**
     * 业务工具后端配置。
     *
     * <p>{@code mode} 决定订单/商品/售后/会员/投诉/知识库六个后端的实现：{@code mock}（进程内示例，默认）|
     * {@code jdbc}（MyBatis-Plus 实现，落 {@code cw_order} 等演示表）。jdbc 模式与各域 store-mode=jdbc 一样，
     * 触发 starter 独立持久化环境（{@code CustomerWorkPersistenceConfig}）装配。</p>
     */
    @Data
    public static class ToolBackend {
        /** 存储模式：mock（进程内示例，默认）| jdbc（MyBatis-Plus 实现）。 */
        private String mode = "mock";
    }

    /** 坐席访问凭证（HMAC 令牌）配置。 */
    private final AgentAccess agentAccess = new AgentAccess();

    /**
     * 智能客服工单配置。
     *
     * <p>{@code store-mode} 决定 {@code TicketService} 的持久化方式（memory 单实例 / jdbc 跨实例共享）；
     * {@code handoff-keywords} 供上层意图识别命中即建单转人工；SLA 阈值中 waiting/processing 仅告警，
     * auto-confirm/auto-close 由 {@code TicketSlaScheduler} 做有意的自动流转兜底。</p>
     */
    @Data
    public static class Ticket {
        /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
        /** 转人工触发关键词（命中即请求转人工）。 */
        private List<String> handoffKeywords = new ArrayList<>(List.of(
            "转人工", "人工客服", "人工服务", "真人客服", "找人工"));
        /** SLA 告警：WAITING_AGENT（无人接单）超过该秒数即 error 告警。 */
        private long slaWaitingSeconds = 300;
        /** SLA 告警：PROCESSING（接单未处理完）超过该秒数即 error 告警。 */
        private long slaProcessingSeconds = 1800;
        /** WAITING_CONFIRM 超过该秒数用户未确认即自动确认（默认 1 天）。 */
        private long autoConfirmSeconds = 86400;
        /** RESOLVED 超过该秒数即自动关闭归档（默认 3 天）。 */
        private long autoCloseSeconds = 259200;
        /** 进行中工单用户空闲超过该秒数即强制关闭（默认 5 分钟，0=禁用）。 */
        private long idleCloseSeconds = 300;
        /** SLA 巡检总开关（关闭则告警与自动流转全部停用）。 */
        private boolean slaEnabled = true;
    }

    /**
     * 敏感词"一次拦截"过滤配置。默认关闭。
     *
     * <p>入站（用户输入）与出站（AI 输出）各过一遍高性能敏感词自动机，命中即处置（BLOCK/MASK/REVIEW）。
     * 零 LLM、微秒级、可解释。词表由 {@code store-mode} 决定持久化方式（memory 带演示种子 / jdbc 跨实例共享）。</p>
     */
    @Data
    public static class SensitiveWord {
        /** 命中 BLOCK 时的掩码字符默认值。 */
        public static final String DEFAULT_MASK_CHAR = "*";
        /** 入站命中 BLOCK 的统一安全话术。 */
        public static final String DEFAULT_INBOUND_SAFE_REPLY = "您的消息包含不当内容，请调整后重试。";
        /** 出站命中 BLOCK 的安全兜底话术（替换 AI 原始回复）。 */
        public static final String DEFAULT_OUTBOUND_SAFE_REPLY = "抱歉，这个问题我暂时无法回答，请换个方式提问或联系人工客服。";

        /** 总开关。默认关闭。 */
        private boolean enabled = false;
        /** 生效方向：inbound（仅入站）| outbound（仅出站）| both（默认）。 */
        private Direction direction = Direction.BOTH;
        /** 存储模式：memory（进程内，带演示种子，默认）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
        /** 词条动作缺省时的兜底动作（防御式：正常词条均带动作，仅脏数据/异常兜底用），默认 BLOCK。 */
        private com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction defaultAction =
            com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction.BLOCK;
        /** 打码字符（取首字符）。 */
        private String maskChar = DEFAULT_MASK_CHAR;
        /** 入站 BLOCK 安全话术。 */
        private String inboundSafeReply = DEFAULT_INBOUND_SAFE_REPLY;
        /** 出站 BLOCK 安全兜底话术。 */
        private String outboundSafeReply = DEFAULT_OUTBOUND_SAFE_REPLY;
        /** 是否开启词表定时刷新（后台改词后自动生效）。默认开启。 */
        private boolean refreshEnabled = true;
        /** 词表刷新轮询间隔（毫秒，默认 60s）；每轮只查一次版本指纹，指纹变了才重建自动机。 */
        private long refreshIntervalMs = 60_000L;
        /** 命中日志落库配置（供后台"命中看板"查询）。 */
        private final HitLog hitLog = new HitLog();

        /**
         * 敏感词命中日志落库配置。
         *
         * <p>默认关闭：命中日志会记录用户原文片段，属于敏感数据，是否留存由使用方按合规要求显式开启。
         * 开启后命中记录经有界队列异步落 {@code cw_sensitive_word_hit_log}，不阻塞对话主链路。</p>
         */
        @Data
        public static class HitLog {
            /** 是否记录命中日志。默认关闭。 */
            private boolean enabled = false;
            /** 存储模式：memory（进程内环形缓冲，默认）| jdbc（落库，后台看板用）。 */
            private String storeMode = "memory";
            /** 异步落库队列容量；队列满时丢弃新记录并计数，绝不阻塞对话链路。 */
            private int queueCapacity = 2048;
            /** 留存的原文片段最大长度（字符），超出截断，避免整段用户输入落库。 */
            private int snippetMaxLength = 64;
        }

        /** 取打码字符（配置为空则回退默认 '*'）。 */
        public char resolveMaskChar() {
            return (maskChar == null || maskChar.isEmpty()) ? DEFAULT_MASK_CHAR.charAt(0) : maskChar.charAt(0);
        }

        public boolean inboundEnabled() {
            return enabled && direction != Direction.OUTBOUND;
        }

        public boolean outboundEnabled() {
            return enabled && direction != Direction.INBOUND;
        }
    }

    /** 敏感词过滤生效方向。 */
    public enum Direction {
        INBOUND, OUTBOUND, BOTH
    }

    /**
     * 终端用户账户鉴权配置。
     *
     * <p>{@code store-mode} 决定 {@code UserAccountService} 的账户持久化方式；{@code jwt-secret} /
     * {@code jwt-expire-hours} 供上层签发用户登录态令牌（starter 只提供账户与密码校验能力，令牌签发在接入层）。</p>
     */
    @Data
    public static class UserAuth {
        /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
        /** 用户登录态 JWT 签名密钥（生产必须用环境变量注入覆盖）。 */
        private String jwtSecret = "";
        /** 用户登录态有效期（小时，默认 7 天）。 */
        private int jwtExpireHours = 168;
        /** 用户头像上传配置。 */
        private final Avatar avatar = new Avatar();

        /**
         * 用户头像上传配置。
         *
         * <p>{@code directory} 为本地磁盘落盘目录（项目无 OSS/MinIO，沿用 {@code ./data/xxx} 本地磁盘约定）；
         * {@code maxSizeBytes} 为单文件大小上限（超过即中断，默认 2MB）；{@code urlPrefix} 为对外访问 URL 前缀，
         * 落在 {@code /api} 下以复用前端 Vite 代理规则。</p>
         */
        @Data
        public static class Avatar {
            /** 头像落盘目录。 */
            private String directory = "./data/avatars";
            /** 单文件大小上限（字节，默认 2MB）。 */
            private long maxSizeBytes = 2L * 1024 * 1024;
            /** 对外访问 URL 前缀（须以 / 开头和结尾）。 */
            private String urlPrefix = "/api/avatars/";
        }
    }

    /**
     * 聊天日志存储配置。
     *
     * <p>{@code store-mode} 决定 {@code ChatLogService} 的消息留痕持久化方式（memory 仅单实例 / jdbc 跨实例）。</p>
     */
    @Data
    public static class ChatLog {
        /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
    }

    /**
     * 智能体调用分段耗时统计配置。
     *
     * <p>{@code enabled} 控制 {@code AgentCallTimingMiddleware} 是否采集（默认开，开销极低：仅
     * nanoTime 计时 + 异步落库，不阻塞响应流）；{@code store-mode} 决定 {@code AgentCallLogStore} 的
     * 持久化方式（memory 仅单实例 / jdbc 跨实例，落 cw_agent_call_log + cw_agent_call_segment 两表）。</p>
     */
    @Data
    public static class CallLog {
        /** 是否启用调用分段耗时采集（默认开启）。 */
        private boolean enabled = true;
        /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
    }

    /**
     * 坐席访问凭证配置（对应 {@code AgentAccessCredential} 的 HMAC 令牌签发/校验）。
     *
     * <p>{@code secret} 为服务端签名密钥（生产必须用环境变量注入覆盖）；{@code expire-hours} 为坐席令牌有效期。</p>
     */
    @Data
    public static class AgentAccess {
        /** HMAC 签名密钥（生产必须用环境变量注入覆盖）。 */
        private String secret = "";
        /** 坐席访问令牌有效期（小时，默认 12）。 */
        private int expireHours = 12;
    }

    /**
     * 用户反馈存储配置。
     *
     * <p>决定 {@code FeedbackService} 的反馈持久化方式：memory 模式下多实例部署时，反馈写在实例 A、
     * 查询落到实例 B 会查不到；jdbc 模式跨实例共享同一份反馈数据。</p>
     */
    @Data
    public static class Feedback {
        /** 存储模式：memory（进程内，默认，仅单实例场景适用）| jdbc（跨实例共享）。 */
        private String storeMode = "memory";
    }

    /**
     * 数据字典存储配置。
     *
     * <p>决定 {@code DictStore} 的实现：memory 模式仅进程内演示种子；jdbc 模式读客服端库
     * {@code cw_dict_type} / {@code cw_dict_item}（后台字典管理页维护的即这两张表）。</p>
     */
    @Data
    public static class Dict {
        /** 存储模式：memory（进程内种子，默认）| jdbc（读客服端库字典表）。 */
        private String storeMode = "memory";
    }

    /** 事实日志配置（三层记忆第三层）。 */
    @Data
    public static class FactLog {
        /** 是否启用只追加事实日志（可审计、跨会话）。 */
        private boolean enabled = true;
        private String directory = "./data/facts";
        /** 单文件最大大小（MB），超过则轮转到 .1 / .2 归档；<=0 禁用轮转。 */
        private int maxFileMb = 10;
        /** 最多保留的归档文件数（超出最旧的自动删除）；<=0 不限制。 */
        private int maxArchivedFiles = 5;
    }

    /** 接入层安全配置。 */
    @Data
    public static class Security {
        private final Auth auth = new Auth();
        private final RateLimit rateLimit = new RateLimit();
        private final ApprovalAuth approvalAuth = new ApprovalAuth();

        /** API Key 鉴权。 */
        @Data
        public static class Auth {
            /** 是否启用鉴权（默认关闭，生产建议开启并配置 api-keys）。 */
            private boolean enabled = false;
            /** 携带 API Key 的请求头名。 */
            private String headerName = "X-API-Key";
            /** 合法 API Key 列表。 */
            private List<String> apiKeys = new ArrayList<>();

            /**
             * 多租户下的 Key→租户映射：{@code key: 租户ID}。
             *
             * <p>开启多租户后，服务端接入方的租户身份就取自这里——Key 是接入方唯一提供的凭据，
             * 也是唯一不可伪造的租户线索（请求头里的租户参数谁都能改）。</p>
             *
             * <p>与 {@code apiKeys} 并存：本映射里的 Key 同样视为合法凭据，无需再往 {@code apiKeys} 抄一份；
             * 只在 {@code apiKeys} 里的 Key 归入默认租户，保证单租户部署升级后行为不变。</p>
             */
            private Map<String, String> tenantKeys = new LinkedHashMap<>();
        }

        /**
         * 限流配置：本节参数是<b>全局兜底层</b>（无规则命中时生效），规则层见 {@code rule-enabled}。
         *
         * <p>两层的关系：{@code cw_rate_limit_rule} 里的规则按路径前缀优先匹配，都不命中才落到本节参数。
         * 不开规则层时行为与规则化之前完全一致。</p>
         */
        @Data
        public static class RateLimit {
            /** 是否启用全局兜底限流。 */
            private boolean enabled = false;
            /** 每分钟允许的最大请求数（全局兜底层）。 */
            private int requestsPerMinute = 120;
            /** 限流算法：fixed-window（固定窗口，默认）| sliding-window（滑动窗口，更平滑防突刺）。 */
            private String algorithm = "fixed-window";
            /** 滑动窗口时间窗大小（秒），仅 algorithm=sliding-window 时生效。 */
            private int windowSeconds = 60;
            /** 是否启用规则层（按路径前缀匹配后台维护的限流规则）。默认关闭。 */
            private boolean ruleEnabled = false;
            /** 规则存储模式：memory（进程内空规则，默认）| jdbc（读 cw_rate_limit_rule，后台可维护）。 */
            private String storeMode = "memory";
            /** 是否开启规则定时刷新（后台改规则后自动生效）。默认开启。 */
            private boolean refreshEnabled = true;
            /** 规则刷新轮询间隔（毫秒，默认 60s）；每轮只查一次版本指纹，指纹变了才换快照。 */
            private long refreshIntervalMs = 60_000L;
        }

        /**
         * 审批操作员身份鉴权（把关退款审批 approve/deny 的身份来源）。
         *
         * <p>关闭时，审批操作员身份取自请求方自报的 {@code operator} 参数，任何人可冒充任意坐席放行退款；
         * 生产必须开启并配置 {@code operators}，使操作员身份改由服务端凭 token 解析，而非客户端自由输入。</p>
         */
        @Data
        public static class ApprovalAuth {
            /** 是否启用（默认关闭；生产建议开启，仅作用于 approve/deny 两个资金放行端点）。 */
            private boolean enabled = false;
            /** 携带审批操作员 token 的请求头名。 */
            private String headerName = "X-Approval-Token";
            /** token → 操作员姓名映射；每个坐席一个独立 token，避免共享凭证导致的责任不可追溯。 */
            private Map<String, String> operators = new LinkedHashMap<>();
        }
    }

    /** Higress AI 网关接入配置。 */
    @Data
    public static class Higress {
        /** 是否启用 Higress 接入。 */
        private boolean enabled = false;
        /** 客户端名称。 */
        private String name = "higress";
        /** Higress MCP 端点 URL。 */
        private String endpoint = "";
        /** 传输类型：sse | streamable-http。 */
        private String transport = "sse";
        /** 工具搜索关键词（Higress 按需路由工具）；留空则不启用工具搜索。 */
        private String toolSearch = "";
        /** 工具搜索返回的最大工具数。 */
        private int maxTools = 10;
        /** 连接超时（秒）。 */
        private int timeoutSeconds = 30;
    }

    /** 多 Agent 编排配置。 */
    @Data
    public static class MultiAgent {
        /** 是否启用多 Agent 编排端点。 */
        private boolean enabled = true;
        /** 编排模式：fanout（并行多专家聚合）| sequential（流水串行细化）。 */
        private String mode = "fanout";
        /** 每个专家 Agent 的 ReAct 最大轮次。 */
        private int maxIters = 6;
        /** 并行 fanout 的最大并发度（同时在跑的专家数上限，&lt;=0 视为 1）。 */
        private int maxConcurrency = 8;
        /** 单个专家调用超时（秒）：超时按错误隔离，不拖垮整体并行。 */
        private long timeoutSeconds = 60;
        /** 智能路由：先用分诊器判断意图，只把问题发给相关专家（省 token / 更准）；关则广播全部专家。 */
        private boolean routingEnabled = true;
        /** 规则快车道：在 LLM 分诊前先用关键词规则命中确定意图（命中即直路由，省一次模型调用、提准降延迟）。 */
        private boolean fastRouteEnabled = true;
        /** reduce 归纳：fanout 后用归纳器把多专家结论二次合成统一口径回复；关则直接拼接各专家结论。 */
        private boolean reduceEnabled = true;
    }

    /** 中断恢复配置。 */
    @Data
    public static class Interrupt {
        /** 是否启用待执行工具恢复：中断后再次调用可无缝恢复被打断的工具调用。 */
        private boolean pendingToolRecoveryEnabled = true;
    }

    /**
     * Nacos 接入配置（配置中心）。
     *
     * <p>把 Agent 的系统提示词托管到 Nacos 配置中心，支持运营侧在不重启服务的情况下热更新提示词
     * （A/B、话术调优）。A2A Agent Card 注册发现需 Nacos AI API 与 a2a SDK，见 README 扩展点。</p>
     */
    @Data
    public static class Nacos {
        private boolean enabled = false;
        private String serverAddr = "localhost:8848";
        private String namespace = "";
        private String group = "DEFAULT_GROUP";
        /** 系统提示词配置项 dataId。 */
        private String promptDataId = "customer-work-system-prompt";
        private String username = "";
        private String password = "";
        private long timeoutMs = 3000;

        /**
         * 服务注册对外暴露的实例 IP（供网关 lb:// 路由回连本实例）。留空则自动探测本机地址
         * （{@code InetAddress.getLocalHost()}）。多网卡 / 容器场景可用环境变量显式指定可达 IP。
         */
        private String instanceIp = "";

        /**
         * 是否启用「运行时配置」热更新（admin 8082 下发的模型/MCP/提示词整体配置）。默认关闭，
         * 不影响任何未接入的下游；开启后启动拉取 + 监听 {@link #runtimeConfigDataId}，热应用到运行中的 8080。
         */
        private boolean runtimeConfigEnabled = false;
        /** 运行时配置项 dataId（与 admin 发布端 data-id 对齐）。 */
        private String runtimeConfigDataId = "customer-work-runtime-config";
        /**
         * 运行时配置里 API Key 密文的 AES 解密密钥（16/24/32 字节）。生产用环境变量
         * {@code CUSTOMER_WORK_CONFIG_AES_KEY} 注入，且必须与 admin 的 {@code ADMIN_AES_SECRET_KEY} 同值，
         * 否则密文解不开、整份配置被判失败并保留旧配置。
         */
        private String configAesKey = "";

        /**
         * 本实例所属租户编码，用于接收<b>按租户灰度</b>的运行时配置。
         *
         * <p>配了就先读 {@code <runtime-config-data-id>-tenant-<租户码>}，读不到才回落主 dataId。
         * 本端并不理解"灰度"，只是多试了一个更具体的 dataId；运营方把灰度版本写进那个 dataId，
         * 名单外的实例自然继续用主 dataId 上的全量版本。留空即不参与灰度（单租户部署无需配置）。</p>
         */
        private String tenantCode = "";
    }

    /** 交互协议配置（AG-UI / TTS）。 */
    @Data
    public static class Protocol {
        private final Agui agui = new Agui();
        private final Tts tts = new Tts();

        /** AG-UI：标准 Agent-UI 事件协议（前端可直接消费的类型化事件流）。 */
        @Data
        public static class Agui {
            private boolean enabled = true;
            /** 是否在事件流中输出推理增量。 */
            private boolean enableReasoning = true;
            /** 是否输出工具调用参数事件。 */
            private boolean emitToolCallArgs = true;
        }

        /** TTS：语音合成（实时多模态）。需 DashScope 实时 TTS 模型与音频输出，默认关闭。 */
        @Data
        public static class Tts {
            private boolean enabled = false;
        }
    }

    /** 运行时与调度配置。 */
    @Data
    public static class Runtime {
        /** 优雅停机：等待在途请求处理完成的超时（秒）。 */
        private int shutdownTimeoutSeconds = 30;
        /** 是否启用定时维护任务。 */
        private boolean schedulerEnabled = false;
        /** 定时维护任务的执行间隔（毫秒）。 */
        private long schedulerFixedDelayMs = 60_000;
    }

    /**
     * 基于 AgentScope 的定时任务调度配置。
     *
     * <p>周期（cron/固定频率）、启停、失败重试统一交给 XXL-JOB 控制台管理，本配置只负责"启动时
     * 把哪些固定任务注册成 XXL-JOB JobHandler"，避免应用内定时器与控制台配置的周期"两处真相"
     * 互相打架。默认关闭，本地无 XXL-JOB 调度中心也能正常启动。</p>
     */
    @Data
    public static class Scheduler {
        /** XXL-JOB 接入配置。 */
        private final XxlJob xxlJob = new XxlJob();
        /** 固定任务列表：启动时逐个注册为 XXL-JOB JobHandler（handler 名 = 任务 name）。 */
        private List<Task> tasks = new ArrayList<>();
        /** 单次任务执行超时（秒）：Agent 同步调用的硬性超时兜底，避免调度线程被永久占用。 */
        private long executeTimeoutSeconds = 300;

        @Data
        public static class XxlJob {
            /** 是否启用 XXL-JOB 接入（默认关闭，本地无调度中心也能正常启动）。 */
            private boolean enabled = false;
            /** XXL-JOB 调度中心地址，多个用逗号分隔。 */
            private String adminAddresses;
            /** 执行器 AppName（需与 XXL-JOB 控制台"执行器管理"注册的一致）。 */
            private String appname = "customer-work-executor";
            /** 执行器内嵌 Netty Server 端口。 */
            private int port = 9999;
            /** 执行器通信 Token（需与调度中心一致；留空表示不校验）。 */
            private String accessToken;
            /** 执行器运行日志存储路径。 */
            private String logPath = "./data/xxl-job/jobhandler";
        }

        /** 固定任务定义：任务名即 XXL-JOB JobHandler 名，需与控制台"任务管理"里的 JobHandler 一致。 */
        @Data
        public static class Task {
            /** 任务名（= XXL-JOB JobHandler 名，全局唯一）。 */
            private String name;
            /** Agent 系统提示词。 */
            private String sysPrompt;
        }
    }

    /**
     * Hook 扩展能力配置。
     *
     * <p>这四个 Hook 都以 Spring Bean 形式注册，由 {@code CustomerServiceAgentFactory} 通过
     * {@code ObjectProvider<Hook>} 自动织入每个会话 Agent；下游应用声明自己的 {@code Hook} Bean
     * 即可一并被织入（Hook 可插拔）。每个 Hook 在关闭时为零副作用透传。</p>
     */
    @Data
    public static class Hooks {
        /** 延迟埋点：端到端 / 每轮推理 / 每个工具耗时 + 首字时间（TTFT）。 */
        private final Latency latency = new Latency();
        /** 出站脱敏：对最终回复中的手机号 / 身份证 / 银行卡 / 邮箱做掩码。 */
        private final Masking masking = new Masking();
        /** 合规审计：把工具调用与最终决策写入只追加审计轨迹。 */
        private final Audit audit = new Audit();
        /** 自我纠错：检测到未调用退款工具却承诺打款时，强制重新推理。 */
        private final SelfCorrection selfCorrection = new SelfCorrection();
        /** 工具调用护栏：执行前注入公共参数 / 对数值参数做上限钳制。 */
        private final ToolGuard toolGuard = new ToolGuard();
        /** 动态生成参数：按用户意图调整推理温度 / 推理强度。 */
        private final DynamicOptions dynamicOptions = new DynamicOptions();
        /** 入站防注入围栏：命中提示词注入/越狱模式的用户输入直接拒绝，不进入模型推理。 */
        private final PromptGuard promptGuard = new PromptGuard();
        /** 间接注入防护：把工具/MCP 返回结果包进隔离标记块，模型不再把其中的文本当指令执行。 */
        private final IndirectInjectionGuard indirectInjectionGuard = new IndirectInjectionGuard();

        /** 延迟埋点配置。开销极低，默认开启。 */
        @Data
        public static class Latency {
            private boolean enabled = true;
            /**
             * 慢请求留证阈值（毫秒）：端到端耗时超过此值的请求会输出一条结构化慢请求日志（含
             * agent/会话/耗时/出错信息）+ 计数指标 {@code customerwork.agent.slow.requests}，
             * 供事后按 requestId 复盘。出错请求无论耗时都留证。<=0 关闭留证（仍保留分位埋点）。
             */
            private long slowRequestThresholdMs = 5000;
        }

        /** 出站脱敏配置。会改写最终回复内容，默认关闭，需显式开启。 */
        @Data
        public static class Masking {
            private boolean enabled = false;
            /** 命中后替换为的占位串。 */
            private String replacement = "***";
            /** 是否脱敏手机号（中国大陆 11 位）。 */
            private boolean maskPhone = true;
            /** 是否脱敏身份证号（15/18 位）。 */
            private boolean maskIdCard = true;
            /** 是否脱敏银行卡号（13~19 位连续数字）。 */
            private boolean maskBankCard = true;
            /** 是否脱敏邮箱。 */
            private boolean maskEmail = true;
            /** 额外自定义脱敏正则（命中即替换为 replacement）。 */
            private List<String> extraPatterns = new ArrayList<>();
            /** 是否对工具返回结果在进入上下文前脱敏（默认关闭，开启可能影响模型可用信息）。 */
            private boolean maskToolResults = false;
        }

        /** 合规审计配置。默认关闭。 */
        @Data
        public static class Audit {
            private boolean enabled = false;
            /** 审计记录中工具入参是否复用脱敏规则（避免把敏感入参写进审计）。 */
            private boolean maskArgs = true;
        }

        /** 自我纠错配置。默认关闭（会触发额外一轮推理）。 */
        @Data
        public static class SelfCorrection {
            private boolean enabled = false;
            /** 单次请求内最多强制纠错的次数（防止无限自我纠错）。 */
            private int maxCorrections = 1;
            /** 视为"已承诺打款/退款"的关键词。 */
            private List<String> paymentKeywords = new ArrayList<>(List.of(
                "已退款", "已打款", "已为您退款", "退款成功", "已经退", "已到账", "款项已退"));
        }

        /** 工具调用护栏配置。默认关闭。 */
        @Data
        public static class ToolGuard {
            /** 破坏性入参命中后改写成的安全占位（避免误删 / 误格式化真正落到工具执行）。 */
            public static final String DESTRUCTIVE_PLACEHOLDER = "[BLOCKED_BY_TOOL_GUARD]";
            /**
             * 破坏性字符串入参的默认拦截正则（不区分大小写）：
             * 覆盖 rm -rf、删除 .agentscope 沙箱工作区、Windows del /f|/s、format 磁盘格式化。
             * 缓解框架 #1898/#1896（沙箱可删 workspace / 跨用户写）。
             */
            public static final List<String> DEFAULT_DESTRUCTIVE_PATTERNS = List.of(
                "rm\\s+-rf",
                "\\.agentscope[/\\\\]workspace",
                "del\\s+/[fs]",
                "format\\s");

            private boolean enabled = false;
            /** 对每个工具调用注入的公共参数（仅当入参中缺失该键时注入）。 */
            private Map<String, String> injectParams = new LinkedHashMap<>();
            /** 数值参数上限钳制：参数名 -> 最大值（超出则改写为最大值）。 */
            private Map<String, Double> numericCaps = new LinkedHashMap<>();
            /**
             * 破坏性命令拦截正则列表（不区分大小写）：命中的字符串入参会被改写为安全占位并告警。
             * 默认覆盖 rm -rf / 删除沙箱 workspace / Windows del/format；可在 yml 中整体覆盖。
             */
            private List<String> destructivePatterns = new ArrayList<>(DEFAULT_DESTRUCTIVE_PATTERNS);
        }

        /**
         * 入站防注入围栏配置。默认关闭。
         *
         * <p>识别常见提示词注入 / 越狱模式（要求忽略先前指令、套取系统提示词、角色扮演绕过限制等），
         * 命中即拒绝——<b>不调用模型</b>，与 {@link ToolGuard} 的"改写入参放行"不同，这里是入站硬拦截，
         * 因为一句已被识别为注入攻击的用户输入没有"安全改写后继续"的合理中间态。</p>
         */
        @Data
        public static class PromptGuard {
            /** 命中拦截后的统一拒绝话术。 */
            public static final String DEFAULT_REFUSAL_REPLY = "抱歉，我无法处理这类请求，请重新描述您的问题。";
            /**
             * 默认注入/越狱模式（不区分大小写）：覆盖"忽略先前指令"“套取系统提示词”“角色扮演绕过限制”
             * 中英文常见表述。可在 yml 中整体覆盖。
             */
            public static final List<String> DEFAULT_INJECTION_PATTERNS = List.of(
                "忽略(以上|之前|上面|上述)[\\s\\S]{0,6}(指令|规则|设定|要求|prompt)",
                "ignore\\s+(the\\s+)?(above|previous|prior)\\s+(instructions?|rules?|prompt)",
                "(输出|显示|展示|告诉我)[\\s\\S]{0,6}(你的|系统)[\\s\\S]{0,6}(prompt|提示词|指令)",
                "reveal\\s+(your\\s+)?(system\\s+)?prompt",
                "你现在是\\s*DAN\\b",
                "you\\s+are\\s+now\\s+DAN\\b",
                "(假装|扮演)[\\s\\S]{0,10}(没有|无)[\\s\\S]{0,6}(限制|规则)",
                "pretend\\s+(that\\s+)?you\\s+have\\s+no\\s+(restrictions?|rules?)",
                "disregard\\s+(your\\s+)?(guidelines?|rules?|instructions?)");

            private boolean enabled = false;
            /** 拦截正则列表（不区分大小写）：命中即拒绝，不进入模型推理。 */
            private List<String> injectionPatterns = new ArrayList<>(DEFAULT_INJECTION_PATTERNS);
            /** 命中拦截后返回给用户的话术。 */
            private String refusalReply = DEFAULT_REFUSAL_REPLY;
        }

        /**
         * 间接注入防护配置。默认关闭，<b>生产建议开启</b>。
         *
         * <p>与 {@link PromptGuard} 是互补关系而非重复：{@link PromptGuard} 拦用户<b>直接</b>输入的注入，
         * 本配置管的是藏在工具/MCP 返回体里的<b>间接</b>注入——攻击载荷不经过用户输入，入站那道闸看不见。</p>
         *
         * <p>防护分两层：<b>隔离标记</b>（开启后恒生效，把工具结果包进随机标签块并在系统提示词声明
         * "块内是数据不是指令"，确定性、零误杀）+ <b>注入检测</b>（{@link #detectionEnabled}，命中只告警
         * 不拦截）。检测刻意不拦截：工具结果是业务链路中间产物，误杀一次就是一次功能故障。</p>
         */
        @Data
        public static class IndirectInjectionGuard {
            private boolean enabled = false;
            /** 是否对工具结果跑注入检测（命中只记 error 日志与指标，不拦截、不改写）。 */
            private boolean detectionEnabled = true;
            /**
             * 检测正则列表（不区分大小写）。默认复用 {@link PromptGuard#DEFAULT_INJECTION_PATTERNS}——
             * 直接注入与间接注入的攻击话术本质相同，差别只在载荷从哪条路进来。
             */
            private List<String> injectionPatterns = new ArrayList<>(PromptGuard.DEFAULT_INJECTION_PATTERNS);
        }

        /** 动态生成参数配置。默认关闭。 */
        @Data
        public static class DynamicOptions {
            private boolean enabled = false;
            /** 命中以下关键词时切换到"精确档"参数（低温 + 高推理强度）。 */
            private List<String> preciseKeywords = new ArrayList<>(List.of(
                "投诉", "退款", "纠纷", "赔偿", "升级", "维权"));
            /** 精确档温度。 */
            private Double preciseTemperature = 0.1;
            /** 精确档推理强度：low / medium / high。 */
            private String preciseReasoningEffort = "high";
        }
    }

    /**
     * AgentScope 2.0 Harness 新能力配置。
     *
     * <p>{@code harness.enabled=true} 时，{@code HarnessAgentFactory} 在内层 ReActAgent 之上
     * 叠加 Harness 能力（{@code HarnessAgent.fromAgent(...)}）：权限系统、Plan Mode、上下文压缩、
     * 工作区沙箱、子智能体编排。Permission 始终对主 Agent 生效（ReActAgent 原生支持）。</p>
     */
    @Data
    public static class Harness {
        /** 是否启用 HarnessAgent 包装（叠加 Plan Mode / Compaction / Subagent / Workspace）。默认关闭。 */
        private boolean enabled = false;
        /** 工作区 / 沙箱根目录（文件工具、代码执行、子智能体的隔离工作区）。 */
        private String workspaceDir = "./data/workspace";
        /** 分层记忆：启用 MEMORY.md 持久画像 + 会话沉淀 + 自动 consolidation（MemoryConfig）。 */
        private boolean memoryEnabled = false;
        /** 环境级记忆（environmentMemory）：跨会话共享的环境记忆标识/文件，留空则不启用。 */
        private String environmentMemory = "";
        /** 超大工具结果落盘（ToolResultEviction）：把超长工具结果落盘、上下文只留占位符与预览。 */
        private boolean toolResultEvictionEnabled = false;
        /** 技能自进化：启用 SkillCurator + 技能管理/晋升工具（成功模式沉淀为可复用技能）。 */
        private boolean skillCuratorEnabled = false;
        /** 额外上下文文件（人格 / 领域知识等，磁盘 Markdown，每轮注入 system prompt），留空则不注入。 */
        private String additionalContextFile = "";
        /** 组织维度（org）：写入 RuntimeContext KV 命名空间，实现 session/user/org 多维隔离。留空则不写。 */
        private String org = "";
        /** 权限系统配置。 */
        private final Permission permission = new Permission();
        /** Plan Mode（只读规划期）配置。 */
        private final PlanMode planMode = new PlanMode();
        /** 子智能体编排配置。 */
        private final Subagent subagent = new Subagent();
        /** 安全沙箱执行配置。 */
        private final Sandbox sandbox = new Sandbox();

        /**
         * 安全沙箱执行：把工具执行 / 代码执行限定在隔离环境内，快照状态可跨进程恢复。
         *
         * <p>Local 与 Docker 沙箱内置于 Harness（无需额外依赖）；Kubernetes / e2b / daytona / AgentRun
         * 远端沙箱需引入对应 {@code agentscope-extensions-sandbox-*}（见 docs/MIGRATION-2.0.md）。</p>
         */
        @Data
        public static class Sandbox {
            /** 沙箱模式：none（不隔离，默认）| local（本地子进程）| docker（容器隔离）。 */
            private String mode = "none";
            /** 隔离粒度：session | user | agent | global。 */
            private String isolationScope = "session";
            /** docker 模式镜像。 */
            private String image = "python:3.11-slim";
            /** local 模式工具/代码执行超时秒数。 */
            private int executeTimeoutSeconds = 60;
        }

        /** 权限系统：控制工具 / 写操作的授权策略。 */
        @Data
        public static class Permission {
            /** 是否启用权限系统（对主 Agent 注入 PermissionContextState）。默认关闭。 */
            private boolean enabled = false;
            /** 权限模式：default | acceptEdits | explore | bypass | dontAsk。 */
            private String mode = "default";
            /** 命中以下工具名时进入"询问/人工确认"（ask 规则）。 */
            private List<String> askTools = new ArrayList<>(List.of("submitRefund", "transferToHuman"));
            /** 命中以下工具名时直接拒绝（deny 规则）。 */
            private List<String> denyTools = new ArrayList<>();
        }

        /** Plan Mode：先只读规划、获批后再写。 */
        @Data
        public static class PlanMode {
            private boolean enabled = false;
            /** Plan Mode 下是否允许执行 Shell。 */
            private boolean allowShell = false;
        }

        /** 子智能体编排（把订单/售后/知识库专家作为 HarnessAgent 的 subagent）。 */
        @Data
        public static class Subagent {
            private boolean enabled = false;
        }
    }
}
