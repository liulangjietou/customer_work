package com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道外部用户 ↔ 工作区会话映射（贫血 DO）。同一 (channelType, appKey, externalUserId) 复用同一
 * {@code sessionId} 保持多轮上下文；reset 时生成新 {@code sessionId} 覆盖以开启新会话。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_channel_session")
public class AiChannelSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String channelType;
    private String appKey;
    /** 渠道侧外部用户唯一标识。 */
    private String externalUserId;
    /** 映射到的工作区会话 ID（ch-<uuid>）。 */
    private String sessionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
