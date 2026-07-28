package com.richard.fyoung.customeradmin.contentguard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 敏感词新增/编辑请求。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SensitiveWordSaveRequest {

    /** 主键；新增留空。 */
    private Long id;

    /** 词面（唯一）。 */
    @NotBlank(message = "敏感词不能为空")
    @Size(max = 128, message = "敏感词长度不能超过 128")
    private String word;

    /** 类目枚举名。 */
    @NotBlank(message = "类目不能为空")
    private String category;

    /** 处置动作枚举名。 */
    @NotBlank(message = "处置动作不能为空")
    private String action;

    /** 是否启用；留空按启用处理。 */
    private Boolean enabled;
}
