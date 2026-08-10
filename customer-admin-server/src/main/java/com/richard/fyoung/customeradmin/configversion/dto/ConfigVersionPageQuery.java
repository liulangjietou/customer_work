package com.richard.fyoung.customeradmin.configversion.dto;

import com.richard.fyoung.customeradmin.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 配置版本分页查询。
 * @author owlzhangfq@gmail.com
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigVersionPageQuery extends PageQuery {

    /** AGENT / MODEL，空表示不筛。 */
    private String configType;

    /** 目标业务编码，空表示不筛。 */
    private String targetCode;
}
