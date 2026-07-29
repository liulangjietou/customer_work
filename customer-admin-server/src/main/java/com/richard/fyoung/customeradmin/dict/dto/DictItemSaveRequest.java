package com.richard.fyoung.customeradmin.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 字典项新增/编辑请求。所属 {@code dictType} 由路径/查询参数携带，编辑时不允许挪类型。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DictItemSaveRequest {

    /** 字典项键（业务值，同一类型下唯一）。 */
    @NotBlank(message = "字典项键不能为空")
    @Size(max = 128, message = "字典项键最长 128 字符")
    private String itemKey;

    /** 字典项标签（展示文案）。 */
    @NotBlank(message = "字典项标签不能为空")
    @Size(max = 128, message = "字典项标签最长 128 字符")
    private String itemLabel;

    /** 排序号，越小越靠前（缺省 0）。 */
    private Integer sort;

    /** 是否启用（缺省 true）。 */
    private Boolean enabled;

    /** 备注说明。 */
    @Size(max = 255, message = "备注最长 255 字符")
    private String remark;
}
