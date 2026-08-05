package com.richard.fyoung.customerwork.sqlkit;

/**
 * SQL 结果列转换类型：DATE_FORMAT（时间格式化）/ VALUE_MAP（值映射）。
 * @author owlzhangfq@gmail.com
 */
public enum TransformType {

    DATE_FORMAT,
    VALUE_MAP;

    /** 名称合法性判断（大小写不敏感）。 */
    public static boolean isValid(String type) {
        if (type == null) {
            return false;
        }
        for (TransformType t : values()) {
            if (t.name().equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }
}
