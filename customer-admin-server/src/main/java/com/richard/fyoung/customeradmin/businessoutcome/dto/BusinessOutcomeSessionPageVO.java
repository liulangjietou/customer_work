package com.richard.fyoung.customeradmin.businessoutcome.dto;

import java.util.List;

/** 会话结果下钻分页。 */
public record BusinessOutcomeSessionPageVO(
    long total,
    int page,
    int size,
    List<BusinessOutcomeSessionVO> records
) {
}
