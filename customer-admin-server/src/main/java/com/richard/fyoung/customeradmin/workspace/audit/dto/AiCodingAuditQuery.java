package com.richard.fyoung.customeradmin.workspace.audit.dto;

import com.richard.fyoung.customeradmin.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 编码审计日志查询条件：通用分页（keyword 匹配操作人、status 复用为结果过滤）之上，
 * 追加 AI 编码域特有的三个精确过滤维度。
 * @author owlzhangfq@gmail.com
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiCodingAuditQuery extends PageQuery {

    /** 智能体编码（精确匹配）。 */
    private String agentCode;

    /** 会话ID（精确匹配，用于追溯单次会话的全部 AI 操作）。 */
    private String sessionId;

    /** 操作类型（{@code AiCodingOperation} 枚举名，精确匹配）。 */
    private String operation;
}
