package com.richard.fyoung.customeradmin.aiconfig.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能体视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentVO {
    private Long id;
    private String agentName;
    private String agentCode;
    private Long modelId;
    private String modelName;
    /** 绑定的模型路由策略；为空表示沿用主备模型链。 */
    private Long modelRoutePolicyId;
    /** 有序备用模型ID（容错切换顺序），字段名为前端契约，不可改动。 */
    private List<Long> backupModelIds;
    /** 与 {@link #backupModelIds} 一一对应的模型名称。 */
    private List<String> backupModelNames;
    private List<Long> mcpIds;
    private List<Long> skillIds;
    private List<Long> systemToolIds;
    private String systemPrompt;
    private List<String> capabilities;
    private String icon;
    private Integer status;
    private LocalDateTime createTime;

    /** 子智能体 id 列表（capabilities 含 subagent 时有意义）。 */
    private List<Long> subAgentIds;
    // ---- 高级参数（null=用框架/工厂默认） ----
    private Integer maxIters;
    private Integer toolTimeoutSeconds;
    private Integer toolMaxAttempts;
    private Integer compressTriggerMsgs;
    private Integer compressKeepMsgs;

    /** 绑定的 RAG 知识库 id 列表。 */
    private List<Long> knowledgeBaseIds;
    /** 与 {@link #knowledgeBaseIds} 一一对应的知识库名称（列表页直接展示，免前端再查一次）。 */
    private List<String> knowledgeBaseNames;
}
