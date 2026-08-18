package com.richard.fyoung.customerwork.safety.subjectquota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 配额等级持久化对象（贫血 DO，对应 {@code cw_subject_quota_level}）。
 *
 * <p>显式持有 {@code tenantId} 的理由同 {@code TenantQuotaDO}：运营方跨租户维护等级时，
 * 要读出"这一档属于谁"，靠拦截器自动补值读不出来。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_subject_quota_level")
public class SubjectQuotaLevelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    private String levelCode;
    private String levelName;
    private String subjectType;
    private Integer windowSeconds;
    private Long tokenLimit;
    private Integer requestLimit;
    private String exceedAction;
    private Integer enabled;
    private String remark;
    private Long createdAtMs;
    private Long updatedAtMs;
}
