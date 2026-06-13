package com.richard.fyoung.customerwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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

    /** 模型层配置。生产建议用环境变量 {@code DASHSCOPE_API_KEY} 注入密钥。 */
    @Data
    public static class Model {
        private String apiKey;
        private String name = "qwen-max";
        private String baseUrl;
        private Double temperature = 0.3;
        private Integer maxTokens = 1500;
        private boolean stream = true;
        /** Embedding 模型名（RAG 接百炼向量检索时使用）。 */
        private String embeddingName = "text-embedding-v3";
    }

    /** 会话持久化配置：memory | json | redis | mysql。 */
    @Data
    public static class Session {
        private String mode = "memory";
        private String directory = "./data/sessions";
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
                    + "&serverTimezone=UTC&characterEncoding=utf8mb4";
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
    }

    /** 事实日志配置（三层记忆第三层）。 */
    @Data
    public static class FactLog {
        /** 是否启用只追加事实日志（可审计、跨会话）。 */
        private boolean enabled = true;
        private String directory = "./data/facts";
    }

    /** 接入层安全配置。 */
    @Data
    public static class Security {
        private final Auth auth = new Auth();
        private final RateLimit rateLimit = new RateLimit();

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
    }

    /** 中断恢复配置。 */
    @Data
    public static class Interrupt {
        /** 是否启用待执行工具恢复：中断后再次调用可无缝恢复被打断的工具调用。 */
        private boolean pendingToolRecoveryEnabled = true;
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
}
