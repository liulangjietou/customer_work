package com.richard.fyoung.customeradmin.system.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 操作日志（含登录/登出日志，需求文档 §5 只列了这一张表，不单独建登录日志表）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {

    /** 结果：操作成功。 */
    public static final int RESULT_SUCCESS = 1;
    /** 结果：操作失败（错误信息落 {@code errorMsg}）。 */
    public static final int RESULT_FAILURE = 0;
    /** 操作已留痕，但业务尚未返回终态。 */
    public static final int RESULT_PENDING = 2;
    public static final String AUDIT_STARTED = "STARTED";
    public static final String AUDIT_COMPLETED = "COMPLETED";
    public static final int DEFAULT_RETENTION_DAYS = 3650;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private String operation;
    private String method;
    private String target;
    private String params;
    /** {@link #RESULT_SUCCESS} / {@link #RESULT_FAILURE}。 */
    private Integer result;
    private String errorMsg;
    private String ip;
    /** 一次操作的稳定审计事件标识。 */
    private String eventId;
    /** STARTED / COMPLETED；STARTED 长期存在表示执行中断或终态补写失败。 */
    private String auditStatus;
    private LocalDateTime retentionUntil;
    private LocalDateTime createTime;

    public void initializeAudit(String status, LocalDateTime now) {
        this.eventId = UUID.randomUUID().toString();
        this.auditStatus = status;
        this.retentionUntil = now.plusDays(DEFAULT_RETENTION_DAYS);
        this.createTime = now;
    }
}
