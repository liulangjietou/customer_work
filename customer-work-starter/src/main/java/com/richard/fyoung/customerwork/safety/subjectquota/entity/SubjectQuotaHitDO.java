package com.richard.fyoung.customerwork.safety.subjectquota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 超限命中记录持久化对象（贫血 DO，对应 {@code cw_subject_quota_hit}）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_subject_quota_hit")
public class SubjectQuotaHitDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private String subjectType;
    private String subjectId;
    private String levelCode;
    private String limitKind;
    private Long used;
    private Long limitValue;
    private Integer windowSeconds;
    private String action;
    private String resource;
    private Long createdAtMs;
}
