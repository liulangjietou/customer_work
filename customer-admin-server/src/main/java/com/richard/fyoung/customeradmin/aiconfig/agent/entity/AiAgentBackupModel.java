package com.richard.fyoung.customeradmin.aiconfig.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体-备用模型关联（有序，{@code sortOrder} 升序即容错切换顺序）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent_backup_model")
public class AiAgentBackupModel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long modelId;
    private Integer sortOrder;
    /** 库侧 DEFAULT CURRENT_TIMESTAMP 填充，插入时留空。 */
    private LocalDateTime createTime;
}
