package com.richard.fyoung.customerwork.capability.semanticcache.entity;

import lombok.Data;

/**
 * 分区聚合结果（贫血数据袋）：{@code SELECT scope_id, COUNT(*) GROUP BY scope_id} 的一行。
 *
 * <p>不直接把
 * {@link com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheScope}
 * 当 {@code resultType}：那是 record，没有 setter，MyBatis 的自动映射落不进去，
 * 得靠构造器映射（依赖编译期 {@code -parameters}）才行——项目里所有查询都是"贫血 DO 接结果、
 * Store 转领域对象"，这里没有理由破例去赌一个编译参数。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class SemanticCacheScopeDO {

    /** 分区键。 */
    private String scopeId;

    /** 该分区当前条目数。 */
    private long entries;
}
