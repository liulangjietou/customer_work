package com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 渠道机器人新建/编辑请求。编辑时 {@code appSecret} 留空/null=不改密文
 * （同 {@code WorkbenchSiteSaveRequest} 手法，避免每次编辑都要重填明文）；新建时 appSecret 必填
 * （校验在 Service 层，因编辑复用同一 DTO 不能用 {@code @NotBlank} 硬约束）。
 * @author owlzhangfq@gmail.com
 */
public record ChannelRobotSaveRequest(
    @NotBlank(message = "channelType 不能为空") String channelType,
    @NotBlank(message = "robotName 不能为空") String robotName,
    @NotBlank(message = "appKey 不能为空") String appKey,
    String appSecret,
    String robotCode,
    /** 微信回调模式：plaintext / safe，非微信渠道忽略。 */
    String callbackMode,
    /** 微信安全模式 EncodingAESKey；编辑留空表示沿用原密文。 */
    String encodingAesKey,
    @NotBlank(message = "agentCode 不能为空") String agentCode,
    /** 会话模式：continuous 持续会话 / per_message 单次问答（空值按 continuous）。 */
    String sessionMode,
    Integer status,
    String remark) {
}
