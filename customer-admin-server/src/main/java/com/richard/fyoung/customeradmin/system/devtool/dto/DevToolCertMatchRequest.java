package com.richard.fyoung.customeradmin.system.devtool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 私钥与证书匹配校验请求。私钥仅在内存中参与一次签名-验签探测，不落库不记日志。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolCertMatchRequest {

    /** 证书 PEM（取第一段 CERTIFICATE 块）。 */
    @NotBlank(message = "证书 PEM 不能为空")
    @Size(max = 256 * 1024, message = "证书内容过大（上限 256KB）")
    private String certPem;

    /** 私钥 PEM（支持 PKCS#8 / PKCS#1 RSA / SEC1 EC，不支持加密私钥）。 */
    @NotBlank(message = "私钥 PEM 不能为空")
    @Size(max = 256 * 1024, message = "私钥内容过大（上限 256KB）")
    private String privateKeyPem;
}
