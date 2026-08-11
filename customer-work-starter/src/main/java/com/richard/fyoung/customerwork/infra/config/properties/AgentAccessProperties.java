package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坐席访问凭证配置（对应 {@code AgentAccessCredential} 的 HMAC 令牌签发/校验）。
 *
 * <p>{@code secret} 为服务端签名密钥（生产必须用环境变量注入覆盖）；{@code expire-hours} 为坐席令牌有效期。</p>
 */
@Data
public class AgentAccessProperties {
    /** HMAC 签名密钥（生产必须用环境变量注入覆盖）。 */
    private String secret = "";
    /** 坐席访问令牌有效期（小时，默认 12）。 */
    private int expireHours = 12;
}
