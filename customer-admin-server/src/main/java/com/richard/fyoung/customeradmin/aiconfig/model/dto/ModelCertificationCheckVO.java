package com.richard.fyoung.customeradmin.aiconfig.model.dto;

/** 单项认证证据；message 必须是脱敏摘要。 */
public record ModelCertificationCheckVO(String code,
                                        String name,
                                        String status,
                                        String measuredValue,
                                        String threshold,
                                        String message) {
}
