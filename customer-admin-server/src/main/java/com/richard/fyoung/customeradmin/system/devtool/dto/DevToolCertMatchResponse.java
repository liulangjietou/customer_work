package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 私钥与证书匹配校验响应。
 * @author owlzhangfq@gmail.com
 */
@Data
@AllArgsConstructor
public class DevToolCertMatchResponse {

    /** 是否配对（私钥签名能被证书公钥验签）。 */
    private boolean matched;

    /** 证书公钥算法。 */
    private String publicKeyAlgorithm;

    /** 结论说明。 */
    private String reason;
}
