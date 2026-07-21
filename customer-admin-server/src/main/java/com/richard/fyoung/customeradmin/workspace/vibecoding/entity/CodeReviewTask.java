package com.richard.fyoung.customeradmin.workspace.vibecoding.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 代码审查异步任务（贫血 DO）：AI 代码审查从"同步等结果"改为"提交-轮询"，模型分钟级调用不再
 * 阻塞前端。提交即落 {@link #STATUS_RUNNING} 行返回 taskId，异步完成后回写
 * {@link #STATUS_SUCCESS}/{@link #STATUS_FAILED} 与结果，并发站内信通知提交人。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_code_review_task")
public class CodeReviewTask {

    /** 状态：执行中。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 状态：成功。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 状态：失败。 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentCode;
    private String sessionId;

    /** 提交人（admin 用户 id）。 */
    private Long userId;

    /** 状态：{@link #STATUS_RUNNING} / {@link #STATUS_SUCCESS} / {@link #STATUS_FAILED}。 */
    private String status;

    /** 审查结果 JSON（{@code ReviewResult} 序列化，SUCCESS 才有）。 */
    private String resultJson;
    /** 失败原因（FAILED 才有）。 */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 完成时间（SUCCESS/FAILED 时写入）。 */
    private LocalDateTime finishTime;
}
