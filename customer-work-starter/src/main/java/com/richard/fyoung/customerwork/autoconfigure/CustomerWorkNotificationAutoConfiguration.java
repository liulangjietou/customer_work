package com.richard.fyoung.customerwork.autoconfigure;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.notification.LoggingNotificationChannel;
import com.richard.fyoung.customerwork.infra.notification.NotificationChannel;
import com.richard.fyoung.customerwork.infra.notification.WebhookNotificationChannel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.util.StringUtils;

/** 主动通知通道自动装配，独立于 infra 组件扫描，便于验证真实出站 Bean 是否生效。 */
@AutoConfiguration(after = CustomerWorkAutoConfiguration.class)
@Conditional(OnCustomerWorkEntryCondition.class)
@ConditionalOnProperty(prefix = "customer-work.modules.infra", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class CustomerWorkNotificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(NotificationChannel.class)
    public NotificationChannel notificationChannel(CustomerWorkProperties properties) {
        if (StringUtils.hasText(properties.getNotification().getWebhookUrl())) {
            return new WebhookNotificationChannel(properties.getNotification());
        }
        // 非生产环境的默认日志回退；生产门禁要求必须配置真实 Webhook。
        return new LoggingNotificationChannel();
    }
}
