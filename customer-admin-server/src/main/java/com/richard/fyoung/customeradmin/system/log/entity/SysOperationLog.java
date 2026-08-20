package com.richard.fyoung.customeradmin.system.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
    private LocalDateTime createTime;
}
