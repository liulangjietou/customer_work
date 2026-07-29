package com.richard.fyoung.customeradmin.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 字典类型新增/编辑请求。编辑时 {@code dictType} 不允许变更（作为字典项的外链键，改编码等于换类型）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DictTypeSaveRequest {

    /** 字典类型编码：小写字母/数字/下划线，2-64 位（如 order_status）。 */
    @NotBlank(message = "字典类型编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,63}$", message = "类型编码须为小写字母开头的小写字母/数字/下划线，2-64 位")
    private String dictType;

    /** 类型名称（展示用）。 */
    @NotBlank(message = "类型名称不能为空")
    @Size(max = 64, message = "类型名称最长 64 字符")
    private String typeName;

    /** 备注说明。 */
    @Size(max = 255, message = "备注最长 255 字符")
    private String remark;

    /** 是否启用（缺省 true）。 */
    private Boolean enabled;
}
