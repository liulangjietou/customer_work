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
