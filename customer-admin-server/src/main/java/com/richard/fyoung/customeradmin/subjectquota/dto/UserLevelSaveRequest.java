package com.richard.fyoung.customeradmin.subjectquota.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户等级分配请求。
 * @author owlzhangfq@gmail.com
 */
@Data
public class UserLevelSaveRequest {

    @NotBlank(message = "用户不能为空")
    private String userId;

    /** 目标等级编码；传空表示回到配置里的默认档。 */
    private String levelCode;
}
