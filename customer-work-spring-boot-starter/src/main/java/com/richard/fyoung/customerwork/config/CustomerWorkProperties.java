package com.richard.fyoung.customerwork.config;

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

        @Data
        public static class Studio {
            private boolean enabled = false;
            private String url = "";
            private String project = "customer-work";
            private String runName = "customer-work-run";
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
        }

        /** 限流（固定时间窗，按 API Key 或客户端 IP 维度）。 */
        @Data
        public static class RateLimit {
            /** 是否启用限流。 */
            private boolean enabled = false;
            /** 每分钟允许的最大请求数。 */
            private int requestsPerMinute = 120;
            /** 限流算法：fixed-window（固定窗口，默认）| sliding-window（滑动窗口，更平滑防突刺）。 */
            private String algorithm = "fixed-window";
            /** 滑动窗口时间窗大小（秒），仅 algorithm=sliding-window 时生效。 */
            private int windowSeconds = 60;
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
