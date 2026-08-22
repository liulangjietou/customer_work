package com.richard.fyoung.customeradmin.billing.dto;

/** 导出的 CSV 文件内容。 */
public record BillingCsvFile(String filename, byte[] content) {
}
