package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.util.List;

/** 创建版本前的纯校验结果，不写库。 */
public record ModelRouteValidationVO(boolean valid, List<ModelRouteConflictVO> conflicts) {
}
