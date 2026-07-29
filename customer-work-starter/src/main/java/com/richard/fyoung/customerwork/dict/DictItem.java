package com.richard.fyoung.customerwork.dict;

/**
 * 字典项领域对象：某个字典类型下的一条键值（key 参与业务匹配，label 供展示）。
 *
 * <p>key 与 label 允许相同（如订单状态直接用中文文案作值）；sort 越小越靠前。</p>
 * @author owlzhangfq@gmail.com
 */
public record DictItem(Long id, String dictType, String itemKey, String itemLabel,
                       int sort, boolean enabled, String remark) {
}
