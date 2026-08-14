package com.richard.fyoung.customerwork.capability.deadletter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 死信持久化对象（贫血数据袋）：与 {@code cw_dead_letter} 表一一映射。
 *
 * <p>领域实体见 {@link com.richard.fyoung.customerwork.capability.deadletter.DeadLetter}
 * （充血，带状态流转与退避计算）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_dead_letter")
public class DeadLetterDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 死信类型：决定由哪个 DeadLetterHandler 重投。 */
    private String type;

    /** 重投所需的完整载荷（JSON），必须自包含。 */
    private String payload;

    /** 关联业务标识（订单号/会话号），供运营检索。 */
    private String bizKey;

    private String status;
    private Integer attempts;
    private String lastError;
    private Long nextRetryAtMs;
    private Long createdAtMs;
    private Long finishedAtMs;
}
