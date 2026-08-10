package com.richard.fyoung.customeradmin.configversion.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 灰度发布请求。
 * @author owlzhangfq@gmail.com
 */
@Data
public class GrayReleaseRequest {

    /** 灰度目标租户编码列表。 */
    @NotEmpty(message = "灰度发布必须指定至少一个租户")
    private List<String> tenantCodes;

    /** 发布说明；建议写清灰度目的，事后翻历史时这句话最有用。 */
    private String remark;
}
