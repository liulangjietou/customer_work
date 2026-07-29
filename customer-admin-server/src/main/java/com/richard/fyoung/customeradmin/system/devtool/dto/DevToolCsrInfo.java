package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.Data;

import java.util.List;

/**
 * 证书签名请求（CSR / PKCS#10）的解析结果视图。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolCsrInfo {

    /** 申请主题（Subject DN）。 */
    private String subject;

    /** 公钥算法（RSA/EC 等）。 */
    private String publicKeyAlgorithm;

    /** 公钥长度（bit；无法判定时为 0）。 */
    private int publicKeyBits;

    /** 签名算法。 */
    private String sigAlgName;

    /** 请求扩展里的使用者可选名称（SAN）。 */
    private List<String> subjectAlternativeNames;
}
