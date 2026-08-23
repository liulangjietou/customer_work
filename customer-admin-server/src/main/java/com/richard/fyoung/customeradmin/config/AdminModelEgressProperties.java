package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 模型端点出网安全配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.model.egress")
public class AdminModelEgressProperties {

    /**
     * 允许访问的模型 host。为空时只放公网；显式配置后才允许对应 host 解析到 RFC1918/ULA 私网。
     * 环回、链路本地/元数据、未指定地址与组播地址不受白名单放宽。
     */
    private List<String> allowedHosts = new ArrayList<>();
}
