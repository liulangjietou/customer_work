package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型视图对象。{@code apiKeyMasked} 只返回固定占位符，真实密文和明文片段永不出现在响应体中。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ModelVO {
    private Long id;
    private Long assetId;
    private String assetCode;
    private String assetName;
    private String vendor;
    private String family;
    private String assetVersion;
    private String modality;
    private Integer contextWindow;
    private Integer maxOutputTokens;
    private Boolean supportsStream;
    private Boolean supportsTool;
    private Boolean supportsJsonSchema;
    private Boolean supportsMultimodal;
    private String assetLifecycleStatus;
    private String modelName;
    private String deploymentCode;
    private String provider;
    private String protocolAdapter;
    private String apiKeyMasked;
    private SecretMetadataVO credential;
    private String baseUrl;
    private String region;
    private String environment;
    private Integer endpointRevision;
    private String lifecycleStatus;
    private Boolean certificationRequired;
    private ModelCertificationVO certification;
    private String model;
    private Boolean isDefault;
    private Integer status;
    private Integer testStatus;
    private LocalDateTime testTime;
    private ModelHealthSnapshotVO health;
    private LocalDateTime createTime;
}
