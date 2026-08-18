package com.richard.fyoung.customeradmin.subjectquota.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 配额等级新增/编辑请求（按当前视角租户 + levelCode 覆盖）。
 *
 * <p><b>刻意不含 tenantId</b>：租户取后端的当前视角，让请求体决定写哪个租户，
 * 等于把越权做成了一个可填字段。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class SubjectQuotaLevelSaveRequest {

    @NotBlank(message = "等级编码不能为空")
    private String levelCode;

    @NotBlank(message = "等级名称不能为空")
    private String levelName;

    /** USER / IP / API_KEY。 */
    private String subjectType;

    /**
     * 滚动窗口长度（秒）。
     *
     * <p>下限 60 秒：更短的窗口在分桶计数下精度已无意义（桶最细到 1 秒，30 桶即 30 秒），
     * 且这种量级的限流该用接入层的路径规则做，不该走按人配额这条链路。</p>
     */
    @Min(value = 60, message = "窗口长度不能小于 60 秒")
    private Integer windowSeconds;

    @Min(value = 0, message = "token 上限不能为负")
    private Long tokenLimit;

    @Min(value = 0, message = "次数上限不能为负")
    private Integer requestLimit;

    /** BLOCK / WARN。 */
    private String exceedAction;

    private Boolean enabled;

    private String remark;
}
