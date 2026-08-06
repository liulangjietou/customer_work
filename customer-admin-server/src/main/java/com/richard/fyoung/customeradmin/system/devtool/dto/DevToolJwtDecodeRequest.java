package com.richard.fyoung.customeradmin.system.devtool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * JWT 解析请求。密钥仅用于本次请求内的签名校验，不落库、不写日志。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolJwtDecodeRequest {

    /** JWT 字符串（header.payload.signature）。 */
    @NotBlank(message = "JWT 不能为空")
    @Size(max = 64 * 1024, message = "JWT 过长（上限 64KB）")
    private String token;

    /** 可选，HS256/HS384/HS512 的签名校验密钥；为空则只解码不校验。 */
    @Size(max = 1024, message = "密钥过长")
    private String secret;

    /** 密钥的文本编码：utf8(默认)/hex/base64。 */
    @Size(max = 16, message = "密钥编码取值非法")
    private String secretEncoding;
}
