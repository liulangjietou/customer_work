package com.richard.fyoung.customeradmin.subjectquota.dto;

import lombok.Data;

/**
 * 超限命中明细展示对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SubjectQuotaHitVO {

    private String subjectType;
    private String subjectId;
    private String levelCode;
    /** TOKEN / REQUEST。 */
    private String limitKind;
    private Long used;
    private Long limitValue;
    private Integer windowSeconds;
    /** BLOCK 真拦了 / WARN 只记录。 */
    private String action;
    /** 触发位置（HTTP 路径或 ws:chat）。 */
    private String resource;
    private Long createdAtMs;
}
