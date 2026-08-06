package com.richard.fyoung.customeradmin.system.devtool.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文本比对请求。两侧允许空串（表示整段新增或整段删除），但不允许缺字段。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolTextDiffRequest {

    /** 原文本。 */
    @NotNull(message = "原文本不能缺失")
    @Size(max = 512 * 1024, message = "原文本过大（上限 512KB）")
    private String oldText;

    /** 新文本。 */
    @NotNull(message = "新文本不能缺失")
    @Size(max = 512 * 1024, message = "新文本过大（上限 512KB）")
    private String newText;

    /** 是否忽略行首尾空白差异。 */
    private Boolean ignoreWhitespace;

    /** 是否忽略大小写差异。 */
    private Boolean ignoreCase;
}
