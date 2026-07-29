package com.richard.fyoung.customerwork.dict;

import java.util.List;

/**
 * 字典存储 SPI（持久化扩展点，运行时只读）。
 *
 * <p>默认 {@link InMemoryDictStore}（进程内、带演示种子，离线可测）；生产可切
 * {@link MybatisDictStore}（{@code customer-work.dict.store-mode=jdbc}，落 {@code cw_dict_type} /
 * {@code cw_dict_item} 两表），或下游声明同类型 Bean 覆盖。</p>
 *
 * <p><b>职责边界</b>：本 SPI 只承担客服端运行时的读取；字典的增删改由后台管理系统直连客服端库
 * 的同两张表完成（照内容风控先例，单一数据真源、不做双写同步）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface DictStore {

    /** 全部启用的字典类型（sort 无关，按 dict_type 排序）。 */
    List<DictType> listEnabledTypes();

    /** 某类型下全部启用的字典项（按 sort 升序）；类型不存在或未配置返回空列表。 */
    List<DictItem> findEnabledItems(String dictType);
}
