package com.richard.fyoung.customeradmin.system.devtool.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * cron 表达式解析响应。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolCronExplainResponse {

    /** 归一化后的表达式（多余空白已压缩）。 */
    private String expression;

    /** 推算所用时区。 */
    private String timezone;

    /** 逐字段释义。 */
    private List<Field> fields;

    /** 后续执行时间（yyyy-MM-dd HH:mm:ss）；表达式不会再触发时为空列表。 */
    private List<String> nextTimes;

    /**
     * cron 单字段释义。
     */
    @Data
    @AllArgsConstructor
    public static class Field {

        /** 字段名（秒/分钟/小时/日/月/星期）。 */
        private String name;

        /** 该字段的原始取值。 */
        private String value;

        /** 该字段的合法取值范围。 */
        private String range;

        /** 取值释义。 */
        private String description;
    }
}
