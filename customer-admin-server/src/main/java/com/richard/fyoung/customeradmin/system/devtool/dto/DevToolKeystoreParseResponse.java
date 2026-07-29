package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.Data;

import java.util.List;

/**
 * 密钥库（PFX/PKCS#12、JKS）解析响应。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolKeystoreParseResponse {

    /** 实际识别出的密钥库类型（PKCS12/JKS）。 */
    private String keystoreType;

    /** 库内条目。 */
    private List<Entry> entries;

    /**
     * 密钥库条目：别名 + 条目类型 + 证书链解析结果。
     */
    @Data
    public static class Entry {

        /** 条目别名。 */
        private String alias;

        /** 条目类型：PRIVATE_KEY（带私钥）| TRUSTED_CERT（仅证书）。 */
        private String entryType;

        /** 证书链（叶子在前）。 */
        private List<DevToolCertInfo> chain;
    }
}
