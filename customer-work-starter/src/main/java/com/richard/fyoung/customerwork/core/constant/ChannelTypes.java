package com.richard.fyoung.customerwork.core.constant;

/**
 * 外部渠道类型编码（{@code ai_channel_robot.channel_type} 列存的值）。
 *
 * <p>后台按它配置机器人、渠道模块按它挑连接器，两个模块互不依赖、只共同依赖 starter，
 * 故编码落在这里。改一边的后果是后台配好的机器人在渠道侧找不到连接器，静默不响应。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ChannelTypes {

    /** 钉钉（Stream 模式接入）。 */
    public static final String DINGTALK = "dingtalk";

    /** 微信公众号。 */
    public static final String WECHAT = "wechat";

    private ChannelTypes() {
    }
}
