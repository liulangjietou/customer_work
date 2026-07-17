package com.richard.fyoung.customeradmin.aiconfig.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 渠道绑定新增/编辑请求。
 * @param channelCode 渠道编码（唯一，[a-z0-9-]）
 * @param agentId     绑定的智能体 ID
 * @param status      0停用 / 1启用（空则默认启用）
 * @author owlzhangfq@gmail.com
 */
public record ChannelBindingSaveRequest(
    @NotBlank(message = "channelCode 不能为空") String channelCode,
    @NotNull(message = "agentId 不能为空") Long agentId,
    Integer status) {
}
