package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 密钥库私钥导出结果。
 *
 * <p>私钥明文只在本次响应中出现一次：不落库、不写日志，前端也不做本地持久化。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@AllArgsConstructor
public class DevToolPrivateKeyExportResponse {

    /** 条目别名。 */
    private String alias;

    /** 私钥算法（RSA/EC 等）。 */
    private String algorithm;

    /** PKCS#8 未加密私钥 PEM。 */
    private String privateKeyPem;
}
