package com.richard.fyoung.customeradmin.slo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** SLO 告警事实 Mapper。 */
public interface SloAlertMapper extends BaseMapper<SloAlert> {

    int insertIgnore(SloAlert alert);

    SloAlert findActiveForUpdate(@Param("tenantId") String tenantId,
                                 @Param("policyId") Long policyId);

    int updateActiveSeen(@Param("id") Long id,
                         @Param("shortBurnRate") BigDecimal shortBurnRate,
                         @Param("longBurnRate") BigDecimal longBurnRate,
                         @Param("seenAt") LocalDateTime seenAt);

    int resolve(@Param("id") Long id,
                @Param("shortBurnRate") BigDecimal shortBurnRate,
                @Param("longBurnRate") BigDecimal longBurnRate,
                @Param("resolvedAt") LocalDateTime resolvedAt);

    int acknowledge(@Param("id") Long id, @Param("tenantId") String tenantId,
                    @Param("ackBy") Long ackBy, @Param("ackAt") LocalDateTime ackAt);
}
