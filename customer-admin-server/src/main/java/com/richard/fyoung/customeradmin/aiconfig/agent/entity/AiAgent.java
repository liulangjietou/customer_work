package com.richard.fyoung.customeradmin.aiconfig.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体配置。{@code capabilities} 落库为逗号分隔字符串（如 {@code "chat,vibecoding"}），
 * Service 层与 {@link com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentVO} 之间转换为 List。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent")
public class AiAgent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentName;
    /** 用于动态菜单路由，[a-z0-9-]+。 */
    private String agentCode;
    private Long modelId;
    private String systemPrompt;
    private String capabilities;
    private String icon;
    /** 0停用 / 1启用。 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
