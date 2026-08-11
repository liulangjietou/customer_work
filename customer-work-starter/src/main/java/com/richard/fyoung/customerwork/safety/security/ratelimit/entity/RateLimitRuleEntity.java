package com.richard.fyoung.customerwork.safety.security.ratelimit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 限流规则持久化对象（贫血数据袋）：与 {@code cw_rate_limit_rule} 表一一映射。
 *
 * <p>{@code dimension} / {@code algorithm} 以枚举名字符串落库，转换收敛在
 * {@link com.richard.fyoung.customerwork.safety.security.ratelimit.MybatisRateLimitRuleStore}。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_rate_limit_rule")
public class RateLimitRuleEntity {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 规则名（运营可读）。 */
    private String ruleName;

    /** 路径前缀，如 /api/customer/chat。 */
    private String pathPrefix;

    /** 计数维度枚举名（API_KEY/IP/GLOBAL）。 */
    private String dimension;

    /** 窗口内允许的最大请求数。 */
    private Integer limitCount;

    /** 算法枚举名（FIXED_WINDOW/SLIDING_WINDOW）。 */
    private String algorithm;

    /** 时间窗（秒）。 */
    private Integer windowSeconds;

    /** 优先级，越小越先匹配。 */
    private Integer priority;

    /** 是否启用（1 启用 / 0 停用）。 */
    private Boolean enabled;

    private Long createdAtMs;
    private Long updatedAtMs;
}
