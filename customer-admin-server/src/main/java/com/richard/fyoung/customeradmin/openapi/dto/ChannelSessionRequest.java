package com.richard.fyoung.customeradmin.openapi.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 渠道会话解析/重置请求：定位一个渠道外部用户 (channelType, appKey, externalUserId)。
 * @author owlzhangfq@gmail.com
 */
public record ChannelSessionRequest(
    @NotBlank(message = "channelType 不能为空") String channelType,
    @NotBlank(message = "appKey 不能为空") String appKey,
    @NotBlank(message = "externalUserId 不能为空") String externalUserId) {
}
