package com.richard.fyoung.customeradmin.workspace.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 编码操作审计日志（需求文档 §5.2/§5.3：操作人、时间、变更文件、模型调用 token 数）。
 *
 * <p>与 {@code sys_operation_log}（系统通用操作账，面向全后台的增删改）职责不同：本表是
 * AI 编码域的专项审计，独有"变更文件清单 / token 用量 / 会话维度"三类可查询字段，
 * 支撑按会话追溯"这轮对话改了哪些文件、花了多少 token"。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_coding_audit_log")
public class AiCodingAuditLog {

    /** 结果：成功。 */
    public static final int RESULT_SUCCESS = 1;
    /** 结果：失败。 */
    public static final int RESULT_FAILURE = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户ID（未认证兜底场景可为空）。 */
    private Long userId;
    /** 操作人账号（冗余存储，用户改名/删除后历史记录仍可读）。 */
    private String username;

    private String agentCode;
    private String sessionId;
    /** 操作类型，取值见 {@link com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation}。 */
    private String operation;

    /** 本次操作产生变更的文件清单（JSON 数组），只读操作/无变更时为空。 */
    private String changedFiles;

    /** 模型输入 token 数；未发生模型调用或框架未返回用量时为空。 */
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;

    /** 操作耗时（毫秒）。 */
    private Long durationMs;

    /** 结果：{@link #RESULT_SUCCESS} / {@link #RESULT_FAILURE}。 */
    private Integer result;
    /** 失败错误码（{@code ResultCode} 枚举名或流终止信号），成功时为空。 */
    private String errorCode;

    private LocalDateTime createTime;

    /** 操作开始时间戳（毫秒），仅用于进程内计算耗时，不落库。 */
    @TableField(exist = false)
    private transient long startMillis;
}
