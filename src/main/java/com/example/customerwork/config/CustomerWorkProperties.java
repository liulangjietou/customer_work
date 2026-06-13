package com.example.customerwork.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用级配置（强类型绑定 {@code customer-work.*}）。
 *
 * <p>统一收口模型、会话持久化、Agent 运行参数三类配置，便于在不同环境
 * （dev / staging / prod）通过 {@code application-*.yml} 或环境变量覆盖，
 * 而无需改动任何业务代码。</p>
 */
@ConfigurationProperties(prefix = "customer-work")
public class CustomerWorkProperties {

    /** 模型层配置（对接百炼 / DashScope 通义千问）。 */
    private final Model model = new Model();

    /** 会话持久化配置（支撑跨进程会话恢复）。 */
    private final Session session = new Session();

    /** Agent 运行时配置。 */
    private final Agent agent = new Agent();

    /** 长期记忆配置（对应深度解析 3.4，跨会话、多租户隔离）。 */
    private final Memory memory = new Memory();

    /** 任务规划配置（对应深度解析 3.3 PlanNotebook）。 */
    private final Plan plan = new Plan();

    public Model getModel() {
        return model;
    }

    public Session getSession() {
        return session;
    }

    public Agent getAgent() {
        return agent;
    }

    public Memory getMemory() {
        return memory;
    }

    public Plan getPlan() {
        return plan;
    }

    /**
     * 模型层配置。API Key 强烈建议用环境变量 {@code DASHSCOPE_API_KEY} 注入，
     * 不要把真实密钥提交到代码仓库。
     */
    public static class Model {
        /** 百炼 / DashScope API Key。 */
        private String apiKey;
        /** 模型名，默认通义千问 qwen-max；可切 qwen-plus / qwen-turbo 等。 */
        private String name = "qwen-max";
        /** 自定义网关地址（如走 Higress AI 网关或百炼兼容模式）；为空则用 SDK 默认。 */
        private String baseUrl;
        /** 采样温度。客服场景偏确定性，默认较低。 */
        private Double temperature = 0.3;
        /** 单次回复最大 token 数。 */
        private Integer maxTokens = 1500;
        /** 是否开启流式输出（逐 token）。 */
        private boolean stream = true;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public boolean isStream() {
            return stream;
        }

        public void setStream(boolean stream) {
            this.stream = stream;
        }
    }

    /**
     * 会话持久化配置。
     *
     * <ul>
     *   <li>{@code memory}：进程内存储，重启即丢，适合本地联调；</li>
     *   <li>{@code json}：文件落盘，单机重启可恢复，适合单实例生产；</li>
     *   <li>分布式多实例生产请扩展为 Redis/MySQL Session（框架内置 RedisSession / MysqlSession）。</li>
     * </ul>
     */
    public static class Session {
        /** 持久化模式：memory | json。 */
        private String mode = "memory";
        /** json 模式下的落盘目录。 */
        private String directory = "./data/sessions";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    /** Agent 运行时配置。 */
    public static class Agent {
        /** ReAct 最大推理-行动轮次，防止失控空转。 */
        private int maxIters = 10;
        /**
         * 是否启用 Meta-Tool（元工具）：允许 Agent 在运行时自主启停工具组，
         * 缓解大量工具时的上下文窗口压力（对应深度解析 3.2）。默认关闭，保持工具全量可见。
         */
        private boolean metaToolEnabled = false;

        public int getMaxIters() {
            return maxIters;
        }

        public void setMaxIters(int maxIters) {
            this.maxIters = maxIters;
        }

        public boolean isMetaToolEnabled() {
            return metaToolEnabled;
        }

        public void setMetaToolEnabled(boolean metaToolEnabled) {
            this.metaToolEnabled = metaToolEnabled;
        }
    }

    /**
     * 长期记忆配置（对应深度解析 3.4）。
     *
     * <p>跨会话沉淀用户事实，并按租户隔离（ToB 硬要求）。本项目内置一个进程内内存实现，
     * 生产可切换为百炼长期记忆 / Mem0 / ReMe（框架均有扩展）。</p>
     */
    public static class Memory {
        /** 是否启用长期记忆。 */
        private boolean longTermEnabled = true;
        /**
         * 租户解析分隔符：sessionId 形如 {@code tenantA:conv-1} 时，分隔符前为租户 ID，
         * 同租户不同会话共享长期记忆；无分隔符则整个 sessionId 视为一个租户。
         */
        private String tenantDelimiter = ":";
        /** 单次召回的最大记忆条数。 */
        private int retrieveTopK = 5;

        public boolean isLongTermEnabled() {
            return longTermEnabled;
        }

        public void setLongTermEnabled(boolean longTermEnabled) {
            this.longTermEnabled = longTermEnabled;
        }

        public String getTenantDelimiter() {
            return tenantDelimiter;
        }

        public void setTenantDelimiter(String tenantDelimiter) {
            this.tenantDelimiter = tenantDelimiter;
        }

        public int getRetrieveTopK() {
            return retrieveTopK;
        }

        public void setRetrieveTopK(int retrieveTopK) {
            this.retrieveTopK = retrieveTopK;
        }
    }

    /** 任务规划配置（对应深度解析 3.3 PlanNotebook）。 */
    public static class Plan {
        /** 是否启用 PlanNotebook，引导 Agent 有序完成长链路任务。 */
        private boolean enabled = true;
        /** 单个计划的最大子任务数。 */
        private int maxSubtasks = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxSubtasks() {
            return maxSubtasks;
        }

        public void setMaxSubtasks(int maxSubtasks) {
            this.maxSubtasks = maxSubtasks;
        }
    }
}
