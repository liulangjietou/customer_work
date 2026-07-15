package com.richard.fyoung.customerchannel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉 Channel 配置（{@code customer-channel.channel.dingtalk.*}）。
 *
 * <p>启用需在钉钉开放平台创建机器人应用，填入 {@code appKey/appSecret/robotCode}。默认关闭。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "customer-channel.channel.dingtalk")
public class DingTalkProperties {

    /** 是否启用钉钉 Channel（Stream 模式接入钉钉机器人）。默认关闭。 */
    private boolean enabled = false;
    /** 钉钉应用 AppKey（ClientId）。 */
    private String appKey = "";
    /** 钉钉应用 AppSecret（ClientSecret）。 */
    private String appSecret = "";
    /** 机器人 robotCode（机器人编码）。 */
    private String robotCode = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getRobotCode() {
        return robotCode;
    }

    public void setRobotCode(String robotCode) {
        this.robotCode = robotCode;
    }

    /** 凭证是否齐全。 */
    public boolean hasCredentials() {
        return notBlank(appKey) && notBlank(appSecret) && notBlank(robotCode);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
