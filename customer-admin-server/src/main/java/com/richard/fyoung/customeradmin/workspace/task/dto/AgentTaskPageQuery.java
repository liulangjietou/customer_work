package com.richard.fyoung.customeradmin.workspace.task.dto;

import lombok.Data;

/**
 * 后台任务分页查询参数。分页字段按 MyBatis-Plus {@code IPage} 原生命名（current/size），
 * 与同处 {@code aiconfig} 菜单下的定时任务列表保持同一套契约，前端复用同一套适配代码。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentTaskPageQuery {

    /** 页码，从 1 开始。 */
    private long current = 1;
    /** 每页条数。 */
    private long size = 10;
    /** 状态精确匹配：PENDING/RUNNING/COMPLETED/FAILED/CANCELLED，空则不限。 */
    private String status;
    /** 父智能体编码精确匹配，空则不限。 */
    private String agentCode;
    /** 关键字：模糊匹配 taskId / subAgentId / parentSessionId。 */
    private String keyword;
}
