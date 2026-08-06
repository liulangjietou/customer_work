package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 结构化数据格式互转响应。
 * @author owlzhangfq@gmail.com
 */
@Data
@AllArgsConstructor
public class DevToolFormatConvertResponse {

    /** 源格式（小写）。 */
    private String sourceFormat;

    /** 目标格式（小写）。 */
    private String targetFormat;

    /** 转换后的内容。 */
    private String result;
}
