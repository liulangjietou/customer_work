package com.richard.fyoung.customeradmin.aiconfig.model.domain;

/** 路由目的；只描述为何选择某个部署，不承载模型或凭据配置。 */
public enum ModelRoutePurpose {
    DEFAULT,
    ECONOMY,
    COMPLEX_REASONING,
    FALLBACK
}
