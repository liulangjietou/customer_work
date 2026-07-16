package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统工具（{@code httpclient} 等）安全参数。
 *
 * <p>{@link Http#allowedHosts} 是 HTTP 请求工具的 SSRF 收口白名单：
 * <ul>
 *   <li>默认（白名单为空）：拒绝环回/内网/链路本地地址，公网放行——保持工具开箱可用但堵住打内网；</li>
 *   <li>白名单非空（收紧模式）：仅放行白名单内的 host（精确域名或 {@code *.example.com} 通配后缀）。</li>
 * </ul>
 * 判定逻辑见 {@code SystemToolHttpGuard}，它是该链路唯一的防御点。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.system-tool")
public class AdminSystemToolProperties {

    private Http http = new Http();

    /** HTTP 请求工具安全参数。 */
    @Data
    public static class Http {

        /**
         * 允许访问的 host 白名单。为空=默认模式（拒内网、放公网）；非空=收紧模式（仅放行白名单）。
         * 支持精确域名（{@code api.example.com}）与通配后缀（{@code *.example.com}，匹配其子域）。
         */
        private List<String> allowedHosts = new ArrayList<>();
    }
}
