package com.richard.fyoung.customeradmin.subjectquota.dto;

import lombok.Data;

/**
 * 配额等级展示对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SubjectQuotaLevelVO {

    private String tenantId;
    private String levelCode;
    private String levelName;
    /** USER / IP / API_KEY。 */
    private String subjectType;
    /** 滚动窗口长度（秒）。 */
    private Integer windowSeconds;
    /** 窗口内 token 上限，0 = 不限。 */
    private Long tokenLimit;
    /** 窗口内请求次数上限，0 = 不限。 */
    private Integer requestLimit;
    /** BLOCK / WARN。 */
    private String exceedAction;
    private Boolean enabled;
    private String remark;
}
