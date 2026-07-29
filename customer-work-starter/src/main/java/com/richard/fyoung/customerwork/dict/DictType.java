package com.richard.fyoung.customerwork.dict;

/**
 * 字典类型领域对象：一组字典项的命名空间（如 order_status = 订单状态）。
 *
 * <p>字典解决的是"就几条枚举数据、不值当单独建表"的场景：新增一类下拉/标签数据时，
 * 在后台字典管理里配一个类型 + 若干项即可，不再新建表与 CRUD。</p>
 * @author owlzhangfq@gmail.com
 */
public record DictType(String dictType, String typeName, String remark, boolean enabled) {
}
