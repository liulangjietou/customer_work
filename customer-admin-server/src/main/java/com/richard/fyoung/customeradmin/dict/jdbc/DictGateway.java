package com.richard.fyoung.customeradmin.dict.jdbc;

import com.richard.fyoung.customerwork.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.dict.mapper.DictTypeMapper;

/**
 * 字典数据门面：把客服端库上的两套 Mapper 打包成一个对象传递。
 *
 * <p>直接复用 starter 的 {@code BaseMapper}（同两张表、同一套列，没有理由重造）；字典数据量小
 * （每类几条到几十条），全量取回内存筛选即可，不需要 admin 侧的分页/聚合 ext Mapper。</p>
 * @author owlzhangfq@gmail.com
 */
public record DictGateway(DictTypeMapper typeMapper, DictItemMapper itemMapper) {
}
