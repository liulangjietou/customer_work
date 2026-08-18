package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/** 主动通知真实出站配置。 */
@Data
public class NotificationProperties {

    /** 通用通知网关 Webhook；空值时回落仅日志实现（生产门禁会拒绝）。 */
    private String webhookUrl = "";
    /** Bearer 凭据；生产默认门禁要求非空，纯 mTLS 场景需显式覆盖通道 Bean 与门禁策略。 */
    private String authToken = "";
    private int timeoutSeconds = 10;
}
