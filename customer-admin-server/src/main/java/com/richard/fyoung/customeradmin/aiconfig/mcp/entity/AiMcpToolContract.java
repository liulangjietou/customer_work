package com.richard.fyoung.customeradmin.aiconfig.mcp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次 MCP 工具契约快照，以及它相对<b>上一条快照</b>的差异。
 *
 * <p>基线就是上一条，不设 {@code is_baseline} 状态位：每次采集插一行，
 * 漂移只在发生的那一次被记下来，新快照自然成为下一次的基线。
 * 形态上像 git log——历史只增，任何时刻都能回答「这个 MCP 当时长什么样」。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_mcp_tool_contract")
public class AiMcpToolContract {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** {@code ai_mcp.id}。 */
    private Long mcpId;

    /** 规范化契约的 SHA-256。 */
    private String contractHash;

    private Integer toolCount;

    /** 规范化后的完整契约，供回溯与 diff 展示。 */
    private String contractJson;

    /** 相对上一条快照的级别：NONE / COMPATIBLE / BREAKING。 */
    private String driftSeverity;

    /** 逐条变更明细（JSON 数组）；无漂移时为空。 */
    private String driftDetail;

    private LocalDateTime capturedAt;

    /** 触发采集的用户；调度采集为空。 */
    private Long capturedBy;

    private LocalDateTime createTime;
}
