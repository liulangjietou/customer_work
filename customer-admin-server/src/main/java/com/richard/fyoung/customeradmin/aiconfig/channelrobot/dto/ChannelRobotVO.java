package com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道机器人列表/详情视图对象。**不返回 appSecret**（密文都不回），仅用 {@code hasSecret}
 * 标识是否已配置密钥，供前端编辑时判断"留空=不改"。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ChannelRobotVO {
    private Long id;
    private String channelType;
    private String robotName;
    private String appKey;
    private String robotCode;
    /** 微信回调模式：plaintext / safe。 */
    private String callbackMode;
    /** 是否已配置 EncodingAESKey 密文。 */
    private Boolean hasEncodingAesKey;
    private String agentCode;
    /** 会话模式：continuous 持续会话 / per_message 单次问答。 */
    private String sessionMode;
    private Integer status;
    private String remark;
    /** 是否已配置 AppSecret 密文（不回明文/密文，只回布尔）。 */
    private Boolean hasSecret;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
