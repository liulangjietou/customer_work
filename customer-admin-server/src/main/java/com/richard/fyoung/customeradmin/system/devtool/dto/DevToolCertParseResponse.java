package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.Data;

import java.util.List;

/**
 * 证书/CSR 解析响应：证书按输入顺序排列（证书链场景第一张通常是叶子证书）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolCertParseResponse {

    /** 解析出的证书列表。 */
    private List<DevToolCertInfo> certificates;

    /** 解析出的 CSR 列表。 */
    private List<DevToolCsrInfo> csrs;
}
