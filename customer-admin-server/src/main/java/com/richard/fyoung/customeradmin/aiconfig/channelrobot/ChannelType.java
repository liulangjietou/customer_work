package com.richard.fyoung.customeradmin.aiconfig.channelrobot;

import lombok.Getter;

import java.util.Arrays;

/**
 * 渠道类型枚举。当前仅 {@link #DINGTALK} 可用（{@code supported=true}），
 * {@link #WECOM}/{@link #WECHAT} 为预留占位（后续接入前不允许创建），避免魔法字符串散落。
 * @author owlzhangfq@gmail.com
 */
@Getter
public enum ChannelType {

    DINGTALK("dingtalk", true),
    WECOM("wecom", false),
    WECHAT("wechat", false);

    private final String code;
    /** 是否已支持接入（预留渠道为 false，暂不允许创建机器人）。 */
    private final boolean supported;

    ChannelType(String code, boolean supported) {
        this.code = code;
        this.supported = supported;
    }

    /** 按 code 查枚举，非法 code 返回 null（由调用方 fast fail）。 */
    public static ChannelType fromCode(String code) {
        return Arrays.stream(values())
            .filter(t -> t.code.equals(code))
            .findFirst()
            .orElse(null);
    }
}
