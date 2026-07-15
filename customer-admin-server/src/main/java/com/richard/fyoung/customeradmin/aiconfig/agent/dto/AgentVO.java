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
}
