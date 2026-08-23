package com.richard.fyoung.customeradmin.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.billing.dto.CostAlertVO;
import com.richard.fyoung.customeradmin.billing.entity.CostAlert;
import com.richard.fyoung.customeradmin.billing.entity.CostAlertStatus;
import com.richard.fyoung.customeradmin.billing.entity.CostAlertType;
import com.richard.fyoung.customeradmin.billing.mapper.CostAlertMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** 金额预算告警的持久化、查询、确认与站内消息投递。 */
@Slf4j
@Service
public class CostAlertService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final String MESSAGE_BIZ_TYPE = "BILLING_COST_ALERT";
    private static final String MESSAGE_LINK = "/system/billing";
    private static final String MESSAGE_ERROR_CODE = "BILLING-ALERT-MESSAGE-FAIL";

    private final CostAlertMapper alertMapper;
    private final SiteMessageService siteMessageService;

    public CostAlertService(CostAlertMapper alertMapper, SiteMessageService siteMessageService) {
        this.alertMapper = alertMapper;
        this.siteMessageService = siteMessageService;
    }

    /**
     * 按业务唯一键创建告警。重复归集只返回 false，不重复投递站内消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean createIfAbsent(CostAlert alert) {
        int inserted = CrossTenantOperations.execute(() -> alertMapper.insertIgnore(alert));
        if (inserted == 0) {
            return false;
        }
        List<Long> userIds = CrossTenantOperations.execute(
            () -> alertMapper.findBillingViewUserIds(alert.getTenantId()));
        for (Long userId : userIds) {
            sendMessage(alert, userId);
        }
        return true;
    }

    /** 控制面可传空租户查看全部；普通租户由 Controller 强制补成自己的租户。 */
    public List<CostAlertVO> list(String tenantId, String status, Integer limit) {
        String normalizedStatus = normalizeStatus(status);
        int rowLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        QueryWrapper<CostAlert> query = new QueryWrapper<CostAlert>()
            .eq(tenantId != null && !tenantId.isBlank(), "tenant_id", tenantId)
            .eq(normalizedStatus != null, "status", normalizedStatus)
            .orderByDesc("first_seen_at", "id")
            .last("LIMIT " + rowLimit);
        return CrossTenantOperations.execute(() -> alertMapper.selectList(query)).stream()
            .map(CostAlertService::toVO)
            .toList();
    }

    /** 按告警所属租户确认；查不到或租户不匹配统一返回资源不存在，避免泄露他租户主键。 */
    @Transactional(rollbackFor = Exception.class)
    public void acknowledge(Long id, String tenantId, Long ackBy) {
        if (id == null || tenantId == null || tenantId.isBlank()) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "金额预算告警不存在");
        }
        CostAlert alert = CrossTenantOperations.execute(() -> alertMapper.selectOne(
            new QueryWrapper<CostAlert>()
                .eq("id", id)
                .eq("tenant_id", tenantId)
                .last("LIMIT 1")));
        if (alert == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "金额预算告警不存在");
        }
        if (CostAlertStatus.ACKED.name().equals(alert.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        CrossTenantOperations.execute(() -> alertMapper.acknowledge(id, tenantId, ackBy, now));
    }

    private void sendMessage(CostAlert alert, Long userId) {
        try {
            TenantContext.runWith(alert.getTenantId(), () -> siteMessageService.send(
                userId,
                title(alert.getAlertType()),
                content(alert),
                MESSAGE_BIZ_TYPE,
                String.valueOf(alert.getId()),
                MESSAGE_LINK));
        } catch (Exception e) {
            // 告警事实比通知通道更重要：消息失败不回滚已经落库的唯一告警。
            log.error("cost alert site message failed, code={}, alertId={}, userId={}",
                MESSAGE_ERROR_CODE, alert.getId(), userId, e);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CostAlertStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "告警状态仅支持 OPEN/ACKED");
        }
    }

    private String title(String alertType) {
        CostAlertType type = CostAlertType.valueOf(alertType);
        return switch (type) {
            case BUDGET_WARNING -> "AI 成本预算预警";
            case BUDGET_EXCEEDED -> "AI 成本预算已超限";
            case FORECAST_EXCEEDED -> "AI 成本预测将超限";
        };
    }

    private String content(CostAlert alert) {
        return String.format("租户 %s 在 %s 周期已消费 %s 元，预算 %s 元，预测 %s 元。",
            alert.getTenantId(), alert.getPeriodKey(), alert.getUsedAmount().toPlainString(),
            alert.getLimitAmount().toPlainString(), alert.getForecastAmount().toPlainString());
    }

    private static CostAlertVO toVO(CostAlert alert) {
        CostAlertVO result = new CostAlertVO();
        result.setId(alert.getId());
        result.setTenantId(alert.getTenantId());
        result.setPeriod(alert.getPeriod());
        result.setPeriodKey(alert.getPeriodKey());
        result.setAlertType(alert.getAlertType());
        result.setUsedAmount(alert.getUsedAmount());
        result.setLimitAmount(alert.getLimitAmount());
        result.setForecastAmount(alert.getForecastAmount());
        result.setStatus(alert.getStatus());
        result.setFirstSeenAt(alert.getFirstSeenAt());
        result.setAckBy(alert.getAckBy());
        result.setAckAt(alert.getAckAt());
        return result;
    }
}
