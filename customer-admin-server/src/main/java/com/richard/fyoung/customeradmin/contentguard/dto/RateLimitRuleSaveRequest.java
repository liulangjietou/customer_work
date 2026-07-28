package com.richard.fyoung.customeradmin.contentguard.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 限流规则新增/编辑请求。
 *
 * <p>阈值与时间窗给了上下界：窗口最长 1 小时、阈值最大 100 万——不是防御式冗余，而是这两个值
 * 一旦被填成天文数字，规则就等同于形同虚设却看起来还开着，运营很难发现。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class RateLimitRuleSaveRequest {

    /** 主键；新增留空。 */
    private Long id;

    /** 规则名（唯一，运营可读）。 */
    @NotBlank(message = "规则名不能为空")
    @Size(max = 64, message = "规则名长度不能超过 64")
    private String ruleName;

    /** 匹配的请求路径前缀。 */
    @NotBlank(message = "路径前缀不能为空")
    @Size(max = 128, message = "路径前缀长度不能超过 128")
    private String pathPrefix;

    /** 计数维度枚举名。 */
    @NotBlank(message = "计数维度不能为空")
    private String dimension;

    /** 窗口内允许的最大请求数。 */
    @NotNull(message = "阈值不能为空")
    @Min(value = 1, message = "阈值至少为 1")
    @Max(value = 1_000_000, message = "阈值过大，规则将形同虚设")
    private Integer limitCount;

    /** 算法枚举名。 */
    @NotBlank(message = "限流算法不能为空")
    private String algorithm;

    /** 时间窗（秒）。 */
    @NotNull(message = "时间窗不能为空")
    @Min(value = 1, message = "时间窗至少 1 秒")
    @Max(value = 3600, message = "时间窗最长 1 小时")
    private Integer windowSeconds;

    /** 优先级，越小越先匹配。 */
    @NotNull(message = "优先级不能为空")
    @Min(value = 0, message = "优先级不能为负")
    private Integer priority;

    /** 是否启用；留空按启用处理。 */
    private Boolean enabled;
}
