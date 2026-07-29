package com.richard.fyoung.customeradmin.dict.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 字典下拉选项（消费端视图，仅启用项）：前端 {@code useDict} 通用 hook 消费的最小结构。
 * @author owlzhangfq@gmail.com
 */
@Data
@AllArgsConstructor
public class DictOptionVO {

    /** 选项值（字典项键）。 */
    private String value;

    /** 选项文案（字典项标签）。 */
    private String label;
}
