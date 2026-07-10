package com.richard.fyoung.customeradmin.workspace.project.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目-会话关联。会话本身没有自增主键（源头是 AgentStateStore 框架表里的逻辑 key），
 * 只能用 {@code agentCode + sessionId} 复合列关联，不是常规的两个数字外键。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_project_session")
public class AiProjectSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String agentCode;
    private String sessionId;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
