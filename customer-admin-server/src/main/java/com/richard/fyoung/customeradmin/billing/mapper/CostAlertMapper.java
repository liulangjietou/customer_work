package com.richard.fyoung.customeradmin.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.billing.entity.CostAlert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 金额预算告警 Mapper。 */
public interface CostAlertMapper extends BaseMapper<CostAlert> {

    /** 按业务唯一键插入；重复归集返回 0，不抛唯一键异常。 */
    int insertIgnore(CostAlert alert);

    /** 仅把 OPEN 告警改成 ACKED，支持并发幂等确认。 */
    int acknowledge(@Param("id") Long id,
                    @Param("tenantId") String tenantId,
                    @Param("ackBy") Long ackBy,
                    @Param("ackAt") LocalDateTime ackAt);

    /** 找出目标租户内拥有账单查看权限的启用用户，作为站内告警接收人。 */
    List<Long> findBillingViewUserIds(@Param("tenantId") String tenantId);
}
