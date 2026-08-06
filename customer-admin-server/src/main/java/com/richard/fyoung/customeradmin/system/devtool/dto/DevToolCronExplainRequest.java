package com.richard.fyoung.customeradmin.system.devtool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * cron 表达式解析请求。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolCronExplainRequest {

    /** 6 段 cron（秒 分 时 日 月 周），与 XXL-JOB 调度中心一致。 */
    @NotBlank(message = "cron 表达式不能为空")
    @Size(max = 200, message = "cron 表达式过长")
    private String expression;

    /** 推算的后续执行时间条数，1~20，为空取默认 5。 */
    @Min(value = 1, message = "推算条数最少 1 条")
    @Max(value = 20, message = "推算条数最多 20 条")
    private Integer count;

    /** 时区 ID，为空取 Asia/Shanghai。 */
    @Size(max = 64, message = "时区 ID 过长")
    private String timezone;
}
