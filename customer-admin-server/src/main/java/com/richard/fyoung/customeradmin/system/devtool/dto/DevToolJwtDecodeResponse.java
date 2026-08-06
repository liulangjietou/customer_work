package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.Data;

/**
 * JWT 解析响应。时间类字段均已按 Asia/Shanghai 格式化为 yyyy-MM-dd HH:mm:ss，缺失的声明为 null。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolJwtDecodeResponse {

    /** 签名算法（header.alg）。 */
    private String algorithm;

    /** 令牌类型（header.typ）。 */
    private String type;

    /** header 的格式化 JSON。 */
    private String header;

    /** payload 的格式化 JSON。 */
    private String payload;

    /** 签发方 iss。 */
    private String issuer;

    /** 主体 sub。 */
    private String subject;

    /** 受众 aud（原样 JSON，可能是字符串或数组）。 */
    private String audience;

    /** 令牌 ID jti。 */
    private String jwtId;

    /** 签发时间 iat。 */
    private String issuedAt;

    /** 生效时间 nbf。 */
    private String notBefore;

    /** 过期时间 exp。 */
    private String expiresAt;

    /** 是否已过期（无 exp 声明时为 false）。 */
    private boolean expired;

    /** 是否尚未生效（无 nbf 声明时为 false）。 */
    private boolean notYetValid;

    /** 距过期的秒数，负数表示已过期；无 exp 声明时为 null。 */
    private Long secondsRemaining;

    /** 是否是 alg=none 的无签名令牌（签名可被任意伪造）。 */
    private boolean unsigned;

    /** 签名校验结论：VALID / INVALID / NOT_CHECKED / UNSUPPORTED_ALG。 */
    private String signatureStatus;
}
