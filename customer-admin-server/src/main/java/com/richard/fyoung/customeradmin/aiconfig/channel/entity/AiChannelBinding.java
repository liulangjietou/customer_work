package com.richard.fyoung.customeradmin.aiconfig.channel.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道-客服机器人运行配置绑定。一个渠道（{@code channelCode}，唯一）绑定一个智能体（{@code agentId}），
 * 标记该智能体的模型/提示词/MCP 配置即客服机器人（8080）的运行时配置来源。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_channel_binding")
public class AiChannelBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道编码（唯一）。 */
    private String channelCode;
    /** 绑定的智能体 ID。 */
    private Long agentId;
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
