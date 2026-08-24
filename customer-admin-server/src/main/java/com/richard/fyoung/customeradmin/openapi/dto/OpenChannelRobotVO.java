package com.richard.fyoung.customeradmin.openapi.dto;

import lombok.Data;

/**
 * 开放 API 渠道机器人视图：**含解密后的 appSecret 明文**（供 customer-channel 侧签名/换 token 用），
 * 仅对已通过 X-Open-Api-Token 鉴权的可信调用方下发。{@code version} 为 update_time 毫秒时间戳，
 * 供调用方 diff 感知配置变更后热加载。
 * @author owlzhangfq@gmail.com
 */
@Data
public class OpenChannelRobotVO {
    private Long id;
    private String channelType;
    private String robotName;
    private String appKey;
    /** 解密后的 AppSecret 明文。 */
    private String appSecret;
    private String robotCode;
    /** 微信回调模式：plaintext / safe。 */
    private String callbackMode;
    /** 解密后的微信 EncodingAESKey，仅对可信 channel 服务下发。 */
    private String encodingAesKey;
    private String agentCode;
    /** 会话模式：continuous 持续会话 / per_message 单次问答。 */
    private String sessionMode;
    /** update_time 的毫秒时间戳，用于调用方感知变更。 */
    private Long version;
}
