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
}
