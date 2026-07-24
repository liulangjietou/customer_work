package com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道机器人（贫血 DO）。{@code appSecretCipher} 存 AES-GCM 密文（AppSecret），永不明文回列表；
 * {@code agentCode} 绑定 {@code ai_agent.agent_code}，开放 API 据此路由到工作区智能体对话。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_channel_robot")
public class AiChannelRobot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道类型：dingtalk（预留 wecom/wechat）。 */
    private String channelType;
    private String robotName;
    /** 渠道 AppKey / ClientId。 */
    private String appKey;
    /** AppSecret 的 AES-GCM 密文。 */
    private String appSecretCipher;
    /** 机器人编码（钉钉 robotCode 等，选填）。 */
    private String robotCode;
    /** 绑定的智能体编码。 */
    private String agentCode;
    /** 会话模式：continuous 持续会话 / per_message 单次问答。 */
    private String sessionMode;
    /** 0停用 / 1启用。 */
    private Integer status;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
