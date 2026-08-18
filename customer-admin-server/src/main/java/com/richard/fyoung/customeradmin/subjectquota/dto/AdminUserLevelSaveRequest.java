package com.richard.fyoung.customeradmin.subjectquota.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台用户等级分配请求。
 * @author owlzhangfq@gmail.com
 */
@Data
public class AdminUserLevelSaveRequest {

    @NotNull(message = "用户不能为空")
    private Long userId;

    /** 目标等级编码；传空表示回到配置里的默认档。 */
    private String levelCode;
}
