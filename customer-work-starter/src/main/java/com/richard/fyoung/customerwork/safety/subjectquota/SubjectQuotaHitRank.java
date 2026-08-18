package com.richard.fyoung.customerwork.safety.subjectquota;

import lombok.Data;

/**
 * 超限命中排行（后台"谁在刷"看板的一行）。
 *
 * <p>是 MyBatis 的聚合查询结果承载体，故用可变 POJO 而非 record——
 * 聚合列名到字段的映射走 setter，与其它 XML 查询保持一致。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class SubjectQuotaHitRank {

    /** 主体类型（字符串形态，直接来自聚合结果）。 */
    private String subjectType;
    private String subjectId;
    private String levelCode;
    /** 统计区间内的命中次数。 */
    private Long hitCount;
    /** 最近一次命中时刻。 */
    private Long lastHitAtMs;
}
