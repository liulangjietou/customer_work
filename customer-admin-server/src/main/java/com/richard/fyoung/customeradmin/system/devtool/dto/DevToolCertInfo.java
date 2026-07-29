package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.Data;

import java.util.List;

/**
 * 单张 X.509 证书的解析结果视图。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolCertInfo {

    /** 使用者（Subject DN）。 */
    private String subject;

    /** 颁发者（Issuer DN）。 */
    private String issuer;

    /** 序列号（十六进制）。 */
    private String serialNumberHex;

    /** X.509 版本（1/2/3）。 */
    private int version;

    /** 生效时间（毫秒时间戳）。 */
    private long notBeforeMs;

    /** 过期时间（毫秒时间戳）。 */
    private long notAfterMs;

    /** 当前是否已过期（或未生效）。 */
    private boolean expired;

    /** 距过期剩余天数（已过期为负数）。 */
    private long daysRemaining;

    /** 签名算法（如 SHA256withRSA）。 */
    private String sigAlgName;

    /** 公钥算法（RSA/EC/Ed25519 等）。 */
    private String publicKeyAlgorithm;

    /** 公钥长度（bit；无法判定时为 0）。 */
    private int publicKeyBits;

    /** 是否 CA 证书（BasicConstraints；证书未携带该扩展时为 false）。 */
    private boolean ca;

    /** 使用者可选名称（SAN，DNS:xx / IP:xx）。 */
    private List<String> subjectAlternativeNames;

    /** 密钥用法（digitalSignature/keyEncipherment 等）。 */
    private List<String> keyUsages;

    /** SHA-1 指纹（冒号分隔十六进制）。 */
    private String sha1Fingerprint;

    /** SHA-256 指纹（冒号分隔十六进制）。 */
    private String sha256Fingerprint;
}
