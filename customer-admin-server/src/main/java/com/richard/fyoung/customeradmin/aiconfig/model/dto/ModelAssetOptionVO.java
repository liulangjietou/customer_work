package com.richard.fyoung.customeradmin.aiconfig.model.dto;

/** 模型部署表单可选择的目录资产。 */
public record ModelAssetOptionVO(Long id,
                                 String assetCode,
                                 String assetName,
                                 String vendor,
                                 String modelKey,
                                 String family,
                                 String assetVersion,
                                 String lifecycleStatus) {
}
