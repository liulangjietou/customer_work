package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto;

import lombok.Data;

/**
 * 定时任务分页查询参数：按 MyBatis-Plus {@code IPage} 原生字段命名（current/size），
 * 分页响应也直接透出 {@code IPage}，不经过 {@code PageResult}（pageNum/pageSize/list）二次包装
 * ——与本模块之外的 mcp/model/skill 等既有列表接口的分页契约不同，仅本模块如此，前端按此单独适配。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ScheduledTaskPageQuery {
    /** 页码，从 1 开始。 */
    private long current = 1;
    /** 每页条数。 */
    private long size = 10;
    /** 关键字：模糊匹配 taskCode / taskName。 */
    private String keyword;
}
