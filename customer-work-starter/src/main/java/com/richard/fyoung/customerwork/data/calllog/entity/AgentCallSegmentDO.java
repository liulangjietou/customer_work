package com.richard.fyoung.customerwork.data.calllog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 智能体调用分段明细表 {@code cw_agent_call_segment} 的持久化对象（贫血 DO）。
 *
 * <p>{@code callLogId} 外键指向 {@code cw_agent_call_log.id}；{@code kind} 以枚举名字符串存储；
 * {@code startTime} 为分段开始毫秒时间戳。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_agent_call_segment")
public class AgentCallSegmentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long callLogId;
    private Integer seq;
    private String kind;
    private String name;
    private Long startTime;
    private Long durationMs;
    private Long inputTokens;
    private Long outputTokens;
    /** 命中缓存的输入 token（inputTokens 的子集，仅 MODEL 段）。 */
    private Long cachedTokens;
    /** 模型自报耗时（毫秒，仅 MODEL 段）。 */
    private Long modelReportedMs;
    private String provider;
    private Long deploymentId;
    private String modelName;
    private Long priceId;
    private String currency;
    private BigDecimal inputUnitPrice;
    private BigDecimal outputUnitPrice;
    private BigDecimal cachedUnitPrice;
    private String pricingStatus;
    /** 按冻结价目结算的本分段金额；未完整结算时为空。 */
    private BigDecimal costAmount;
    private String costCurrency;
    private String costStatus;
    private Boolean success;
    private String errorMsg;
}
