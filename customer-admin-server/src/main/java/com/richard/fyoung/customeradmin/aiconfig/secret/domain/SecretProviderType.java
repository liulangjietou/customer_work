package com.richard.fyoung.customeradmin.aiconfig.secret.domain;

/** SecretRef 后端类型。第一切片只启用 LOCAL_AES。 */
public enum SecretProviderType {
    LOCAL_AES,
    VAULT,
    AWS_SM,
    AZURE_KV,
    GCP_SM,
    ENV
}
