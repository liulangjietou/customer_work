package com.richard.fyoung.customeradmin.billing.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/** 日账单结算窗口与对账查询约束。 */
@Data
@ConfigurationProperties(prefix = "admin.billing.settlement")
public class BillingSettlementProperties {

    /** 自然日所属时区；调用事实本身仍使用 epoch millis。 */
    private String zoneId = "Asia/Shanghai";
    /** 单次对账允许的最大自然日范围。 */
    private int maxReconciliationDays = 31;

    public ZoneId zone() {
        try {
            return ZoneId.of(zoneId);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "账单结算时区配置不合法");
        }
    }
}
