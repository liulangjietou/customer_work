package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据字典存储配置。
 *
 * <p>决定 {@code DictStore} 的实现：memory 模式仅进程内演示种子；jdbc 模式读客服端库
 * {@code cw_dict_type} / {@code cw_dict_item}（后台字典管理页维护的即这两张表）。</p>
 */
@Data
public class DictProperties {
    /** 存储模式：memory（进程内种子，默认）| jdbc（读客服端库字典表）。 */
    private String storeMode = "memory";
}
