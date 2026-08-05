package com.richard.fyoung.customerwork.sqlkit;

/**
 * SQL 查询参数类型：决定执行时的绑定值转换（INTEGER→Long，STRING/DATETIME 透传字符串）。
 * @author owlzhangfq@gmail.com
 */
public enum ParamType {

    STRING,
    INTEGER,
    DATETIME;

    /** 名称合法性判断（大小写不敏感）。 */
    public static boolean isValid(String type) {
        if (type == null) {
            return false;
        }
        for (ParamType t : values()) {
            if (t.name().equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }
}
