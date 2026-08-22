package com.richard.fyoung.customeradmin.slo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.slo.dto.SloPolicySaveRequest;
import com.richard.fyoung.customeradmin.slo.dto.SloPolicyVO;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloPolicyMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** 当前租户的 SLO 策略维护。 */
@Service
public class SloPolicyService {

    private final SloPolicyMapper policyMapper;

    public SloPolicyService(SloPolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    public List<SloPolicyVO> list() {
        String tenantId = requireTenant();
        return policyMapper.selectList(new QueryWrapper<SloPolicy>()
                .eq("tenant_id", tenantId)
                .orderByDesc("update_time", "id"))
            .stream().map(SloPolicyService::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long upsert(SloPolicySaveRequest request) {
        String tenantId = requireTenant();
        String scopeType = normalizeScope(request.scopeType());
        String scopeKey = normalizeScopeKey(scopeType, request.scopeKey());
        if (request.shortWindowMinutes() >= request.longWindowMinutes()) {
            throw new BizException(ResultCode.PARAM_INVALID, "短窗口必须小于长窗口");
        }
        SloPolicy policy = request.id() == null ? new SloPolicy() : requirePolicy(request.id(), tenantId);
        policy.setTenantId(tenantId);
        policy.setPolicyName(request.policyName().trim());
        policy.setScopeType(scopeType);
        policy.setScopeKey(scopeKey);
        policy.setAvailabilityTarget(request.availabilityTarget());
        policy.setLatencyTarget(request.latencyTarget());
        policy.setLatencyThresholdMs(request.latencyThresholdMs());
        policy.setShortWindowMinutes(request.shortWindowMinutes());
        policy.setLongWindowMinutes(request.longWindowMinutes());
        policy.setMinimumSampleCount(resolveMinimumSampleCount(request.minimumSampleCount(), policy));
        policy.setBurnRateThreshold(request.burnRateThreshold());
        policy.setEnabled(request.enabled() == null || request.enabled());
        if (policy.getId() == null) {
            policyMapper.insert(policy);
        } else {
            policyMapper.update(policy, new QueryWrapper<SloPolicy>()
                .eq("id", policy.getId()).eq("tenant_id", tenantId));
        }
        return policy.getId();
    }

    SloPolicy requirePolicy(Long id, String tenantId) {
        SloPolicy policy = policyMapper.selectOne(new QueryWrapper<SloPolicy>()
            .eq("id", id).eq("tenant_id", tenantId).last("LIMIT 1"));
        if (policy == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "SLO 策略不存在");
        }
        return policy;
    }

    static String requireTenant() {
        String tenantId = TenantSession.effectiveTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文");
        }
        return tenantId;
    }

    private String normalizeScope(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!List.of("TENANT", "AGENT", "CHANNEL").contains(normalized)) {
            throw new BizException(ResultCode.PARAM_INVALID, "SLO 范围仅支持 TENANT/AGENT/CHANNEL");
        }
        return normalized;
    }

    private String normalizeScopeKey(String scopeType, String raw) {
        if ("TENANT".equals(scopeType)) {
            return null;
        }
        if (raw == null || raw.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "Agent 或渠道范围必须填写范围键");
        }
        return raw.trim();
    }

    private int resolveMinimumSampleCount(Integer requestedCount, SloPolicy policy) {
        if (requestedCount != null) {
            return requestedCount;
        }
        if (policy.getMinimumSampleCount() != null) {
            return policy.getMinimumSampleCount();
        }
        return SloPolicy.DEFAULT_MINIMUM_SAMPLE_COUNT;
    }

    private static SloPolicyVO toVO(SloPolicy p) {
        return new SloPolicyVO(p.getId(), p.getPolicyName(), p.getScopeType(), p.getScopeKey(),
            p.getAvailabilityTarget(), p.getLatencyTarget(), p.getLatencyThresholdMs(),
            p.getShortWindowMinutes(), p.getLongWindowMinutes(), p.getMinimumSampleCount(),
            p.getBurnRateThreshold(), p.getEnabled(), p.getUpdateTime());
    }
}
