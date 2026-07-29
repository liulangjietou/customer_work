package com.richard.fyoung.customeradmin.system.devtool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 证书/CSR 解析请求：粘贴任意 PEM 文本（可同时含多张证书与 CSR，按块识别）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolCertParseRequest {

    /** PEM 文本（-----BEGIN CERTIFICATE----- / -----BEGIN CERTIFICATE REQUEST----- 块）。 */
    @NotBlank(message = "PEM 内容不能为空")
    @Size(max = 256 * 1024, message = "PEM 内容过大（上限 256KB）")
    private String pemContent;
}
