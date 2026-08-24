package com.richard.fyoung.customeradmin.slo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.slo.domain.SloAlertEventType;
import com.richard.fyoung.customeradmin.slo.domain.SloAlertStatus;
import com.richard.fyoung.customeradmin.slo.dto.SloAlertEventVO;
import com.richard.fyoung.customeradmin.slo.dto.SloAlertVO;
import com.richard.fyoung.customeradmin.slo.dto.SloWindowEvaluation;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import com.richard.fyoung.customeradmin.slo.entity.SloAlertEvent;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloAlertEventMapper;
import com.richard.fyoung.customeradmin.slo.mapper.SloAlertMapper;
import com.richard.fyoung.customeradmin.slo.mapper.SloPolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** SLO 告警 OPEN/ACKED/RESOLVED 状态机及恢复事件。 */
@Service
public class SloAlertService {

    private static final String ALERT_TYPE = "MULTI_WINDOW_BURN";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final SloAlertMapper alertMapper;
    private final SloAlertEventMapper eventMapper;
    private final SloPolicyMapper policyMapper;
    private final SloNotificationService notificationService;

    public SloAlertService(SloAlertMapper alertMapper,
                           SloAlertEventMapper eventMapper,
                           SloPolicyMapper policyMapper,
                           SloNotificationService notificationService) {
        this.alertMapper = alertMapper;
        this.eventMapper = eventMapper;
        this.policyMapper = policyMapper;
        this.notificationService = notificationService;
    }

    Transition reconcile(SloPolicy policy, String tenantId, LocalDateTime now,
                         boolean burning, boolean healthy,
                         SloWindowEvaluation shortWindow, SloWindowEvaluation longWindow,
                         long windowEndMinute) {
        if (burning) {
            return openOrRefresh(policy, tenantId, now, shortWindow, longWindow, windowEndMinute);
        }
        if (healthy) {
            return resolveIfActive(policy, tenantId, now, shortWindow, longWindow);
        }
        return Transition.NONE;
    }

    @Transactional(rollbackFor = Exception.class)
    public void acknowledge(Long alertId, Long actorUserId) {
        String tenantId = SloPolicyService.requireTenant();
        SloAlert alert = requireAlert(alertId, tenantId);
        if (SloAlertStatus.ACKED.name().equals(alert.getStatus())) {
            return;
        }
        if (SloAlertStatus.RESOLVED.name().equals(alert.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "已恢复的 SLO 告警不能确认");
        }
        LocalDateTime now = LocalDateTime.now();
        if (alertMapper.acknowledge(alertId, tenantId, actorUserId, now) != 1) {
            SloAlert latest = requireAlert(alertId, tenantId);
            if (SloAlertStatus.ACKED.name().equals(latest.getStatus())) {
                return;
            }
            throw new BizException(ResultCode.PARAM_INVALID, "SLO 告警状态已变化，请刷新后重试");
        }
        alert.setStatus(SloAlertStatus.ACKED.name());
        alert.setAckBy(actorUserId);
        alert.setAckAt(now);
        SloPolicy policy = requirePolicy(alert.getPolicyId(), tenantId);
        appendEvent(policy, alert, SloAlertEventType.ACKED, actorUserId,
            alert.getShortBurnRate(), alert.getLongBurnRate(), now);
    }

    public List<SloAlertVO> list(String status, Integer limit) {
        String tenantId = SloPolicyService.requireTenant();
        String normalizedStatus = normalizeStatus(status);
        int rowLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<SloAlert> alerts = alertMapper.selectList(new QueryWrapper<SloAlert>()
            .eq("tenant_id", tenantId)
            .eq(normalizedStatus != null, "status", normalizedStatus)
            .orderByDesc("last_seen_at", "id")
            .last("LIMIT " + rowLimit));
        if (alerts.isEmpty()) {
            return List.of();
        }
        Map<Long, SloPolicy> policies = policyMapper.selectBatchIds(
                alerts.stream().map(SloAlert::getPolicyId).distinct().toList())
            .stream().collect(Collectors.toMap(SloPolicy::getId, Function.identity()));
        return alerts.stream().map(alert -> toVO(alert, policies.get(alert.getPolicyId()))).toList();
    }

    public List<SloAlertEventVO> events(Long alertId) {
        String tenantId = SloPolicyService.requireTenant();
        requireAlert(alertId, tenantId);
        return eventMapper.selectList(new QueryWrapper<SloAlertEvent>()
                .eq("tenant_id", tenantId).eq("alert_id", alertId)
                .orderByAsc("occurred_at", "id"))
            .stream().map(SloAlertService::toEventVO).toList();
    }

    private Transition openOrRefresh(SloPolicy policy, String tenantId, LocalDateTime now,
                                     SloWindowEvaluation shortWindow,
                                     SloWindowEvaluation longWindow,
                                     long windowEndMinute) {
        SloAlert active = alertMapper.findActiveForUpdate(tenantId, policy.getId());
        if (active != null) {
            alertMapper.updateActiveSeen(active.getId(), shortWindow.burnRate(),
                longWindow.burnRate(), now);
            return Transition.NONE;
        }
        SloAlert alert = new SloAlert();
        alert.setTenantId(tenantId);
        alert.setPolicyId(policy.getId());
        alert.setWindowEndMinute(windowEndMinute);
        alert.setAlertType(ALERT_TYPE);
        alert.setActivePolicyId(policy.getId());
        alert.setStatus(SloAlertStatus.OPEN.name());
        alert.setShortBurnRate(shortWindow.burnRate());
        alert.setLongBurnRate(longWindow.burnRate());
        alert.setFirstSeenAt(now);
        alert.setLastSeenAt(now);
        alert.setUpdateTime(now);
        if (alertMapper.insertIgnore(alert) == 0) {
            SloAlert concurrentlyOpened = alertMapper.findActiveForUpdate(tenantId, policy.getId());
            if (concurrentlyOpened == null) {
                throw new IllegalStateException("SLO active alert conflict without active row");
            }
            alertMapper.updateActiveSeen(concurrentlyOpened.getId(), shortWindow.burnRate(),
                longWindow.burnRate(), now);
            return Transition.NONE;
        }
        appendEvent(policy, alert, SloAlertEventType.OPENED, null,
            shortWindow.burnRate(), longWindow.burnRate(), now);
        return Transition.OPENED;
    }

    private Transition resolveIfActive(SloPolicy policy, String tenantId, LocalDateTime now,
                                       SloWindowEvaluation shortWindow,
                                       SloWindowEvaluation longWindow) {
        SloAlert active = alertMapper.findActiveForUpdate(tenantId, policy.getId());
        if (active == null) {
            return Transition.NONE;
        }
        if (alertMapper.resolve(active.getId(), shortWindow.burnRate(), longWindow.burnRate(), now) != 1) {
            throw new IllegalStateException("SLO alert resolution state changed: " + active.getId());
        }
        active.setStatus(SloAlertStatus.RESOLVED.name());
        active.setActivePolicyId(null);
        active.setResolvedAt(now);
        active.setShortBurnRate(shortWindow.burnRate());
        active.setLongBurnRate(longWindow.burnRate());
        appendEvent(policy, active, SloAlertEventType.RESOLVED, null,
            shortWindow.burnRate(), longWindow.burnRate(), now);
        return Transition.RESOLVED;
    }

    private void appendEvent(SloPolicy policy, SloAlert alert, SloAlertEventType type,
                             Long actorUserId, java.math.BigDecimal shortBurnRate,
                             java.math.BigDecimal longBurnRate, LocalDateTime occurredAt) {
        SloAlertEvent event = new SloAlertEvent();
        event.setTenantId(alert.getTenantId());
        event.setAlertId(alert.getId());
        event.setPolicyId(alert.getPolicyId());
        event.setEventType(type.name());
        event.setActorUserId(actorUserId);
        event.setShortBurnRate(shortBurnRate);
        event.setLongBurnRate(longBurnRate);
        event.setOccurredAt(occurredAt);
        eventMapper.insert(event);
        notificationService.enqueue(event, alert, policy);
    }

    private SloAlert requireAlert(Long id, String tenantId) {
        SloAlert alert = alertMapper.selectOne(new QueryWrapper<SloAlert>()
            .eq("id", id).eq("tenant_id", tenantId).last("LIMIT 1"));
        if (alert == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "SLO 告警不存在");
        }
        return alert;
    }

    private SloPolicy requirePolicy(Long id, String tenantId) {
        SloPolicy policy = policyMapper.selectOne(new QueryWrapper<SloPolicy>()
            .eq("id", id).eq("tenant_id", tenantId).last("LIMIT 1"));
        if (policy == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "SLO 策略不存在");
        }
        return policy;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SloAlertStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "告警状态仅支持 OPEN/ACKED/RESOLVED");
        }
    }

    private static SloAlertVO toVO(SloAlert alert, SloPolicy policy) {
        return new SloAlertVO(alert.getId(), alert.getPolicyId(),
            policy == null ? null : policy.getPolicyName(),
            policy == null ? null : policy.getScopeType(),
            policy == null ? null : policy.getScopeKey(),
            alert.getStatus(), alert.getShortBurnRate(), alert.getLongBurnRate(),
            alert.getFirstSeenAt(), alert.getLastSeenAt(), alert.getAckBy(), alert.getAckAt(),
            alert.getResolvedAt());
    }

    private static SloAlertEventVO toEventVO(SloAlertEvent event) {
        return new SloAlertEventVO(event.getId(), event.getEventType(), event.getActorUserId(),
            event.getShortBurnRate(), event.getLongBurnRate(), event.getOccurredAt());
    }

    enum Transition {
        NONE,
        OPENED,
        RESOLVED
    }
}
