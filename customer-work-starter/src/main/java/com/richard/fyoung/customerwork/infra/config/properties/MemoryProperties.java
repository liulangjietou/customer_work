package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 长期记忆配置。 */
@Data
public class MemoryProperties {
    private boolean longTermEnabled = true;
    /** 长期记忆实现：memory（内置内存）| bailian（百炼）| mem0 | reme。 */
    private String provider = "memory";
    /**
     * 内置长期记忆（provider=memory）的存储后端：jdbc（默认，落 cw_long_term_memory 表）| memory（进程内）。
     *
     * <p>默认 jdbc：长期记忆跨会话、跨重启才有意义，进程内实现重启即清空、多副本还各存各的。
     * 持久化环境不可用时自动降级进程内（见 {@code LongTermMemoryStoreConfig}），不阻断启动。</p>
     */
    private String storeMode = "jdbc";
    /**
     * jdbc 召回时的候选集扫描上限：打分在 Java 侧做，先按写入顺序倒序取这么多条再打分。
     * 调大提升长尾记忆的召回率，代价是每次召回的读放大。
     */
    private int recallScanLimit = 500;
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
