package com.richard.fyoung.customeradmin.system.devtool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 结构化数据格式互转请求（JSON / YAML / XML）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolFormatConvertRequest {

    /** 待转换内容。 */
    @NotBlank(message = "内容不能为空")
    @Size(max = 512 * 1024, message = "内容过大（上限 512KB）")
    private String content;

    /** 源格式：json / yaml / xml。 */
    @NotBlank(message = "源格式不能为空")
    @Size(max = 16, message = "源格式取值非法")
    private String sourceFormat;

    /** 目标格式：json / yaml / xml。 */
    @NotBlank(message = "目标格式不能为空")
    @Size(max = 16, message = "目标格式取值非法")
    private String targetFormat;

    /** 转 XML 时的根元素名，为空取 root；其它目标格式忽略。 */
    @Size(max = 64, message = "根元素名过长")
    private String rootName;
}
