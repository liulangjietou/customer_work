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
    private String systemPrompt;
    private List<String> capabilities;
    private String icon;
    private Integer status;
    private LocalDateTime createTime;
}
