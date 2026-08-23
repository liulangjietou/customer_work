package com.richard.fyoung.customeradmin.slo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import org.apache.ibatis.annotations.Insert;

/** SLO 告警事实 Mapper。 */
public interface SloAlertMapper extends BaseMapper<SloAlert> {

    @Insert("""
        INSERT IGNORE INTO ai_slo_alert
          (tenant_id, policy_id, window_end_minute, alert_type, short_burn_rate, long_burn_rate, first_seen_at)
        VALUES
          (#{tenantId}, #{policyId}, #{windowEndMinute}, #{alertType}, #{shortBurnRate}, #{longBurnRate}, #{firstSeenAt})
        """)
    int insertIgnore(SloAlert alert);
}
