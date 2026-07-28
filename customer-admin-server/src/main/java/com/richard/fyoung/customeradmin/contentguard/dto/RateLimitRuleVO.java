package com.richard.fyoung.customeradmin.contentguard.dto;

import lombok.Data;

/**
 * 限流规则展示对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class RateLimitRuleVO {

    private Long id;

    /** 规则名（唯一）。 */
    private String ruleName;

    /** 匹配的请求路径前缀。 */
    private String pathPrefix;

    /** 计数维度：API_KEY/IP/GLOBAL。 */
    private String dimension;

    /** 窗口内允许的最大请求数。 */
    private Integer limitCount;

    /** 算法：FIXED_WINDOW/SLIDING_WINDOW。 */
    private String algorithm;

    /** 时间窗（秒）。 */
    private Integer windowSeconds;

    /** 优先级，越小越先匹配。 */
    private Integer priority;

    /** 是否启用。 */
    private Boolean enabled;

    private Long createdAtMs;
    private Long updatedAtMs;
}
