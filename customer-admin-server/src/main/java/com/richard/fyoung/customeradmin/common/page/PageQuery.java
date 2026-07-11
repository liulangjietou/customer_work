package com.richard.fyoung.customeradmin.common.page;

import lombok.Data;

/**
 * 通用分页查询请求（列表接口统一支持：分页 + 名称搜索 + 状态筛选 + 创建时间排序，需求文档 3.2）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class PageQuery {

    /** 页码，从 1 开始。 */
    private long pageNum = 1;

    /** 每页条数，默认 10（需求文档 3.2）。 */
    private long pageSize = 10;

    /** 名称关键字搜索（各业务域按自身"名称"字段模糊匹配）。 */
    private String keyword;

    /** 状态筛选：0禁用/1启用，不传则不筛选。 */
    private Integer status;

    /** 创建时间排序方向：asc / desc，默认 desc（最新的在前）。 */
    private String sortOrder = "desc";
}
