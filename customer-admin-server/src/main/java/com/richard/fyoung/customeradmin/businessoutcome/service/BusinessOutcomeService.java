package com.richard.fyoung.customeradmin.businessoutcome.service;

import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeAggregateRow;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeDefinitions;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionPageVO;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionRow;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionVO;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSummaryVO;
import com.richard.fyoung.customeradmin.businessoutcome.dto.MetricAvailability;
import com.richard.fyoung.customeradmin.businessoutcome.gateway.BusinessOutcomeGatewayProvider;
import com.richard.fyoung.customeradmin.businessoutcome.mapper.BusinessOutcomeMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;

/**
 * 业务结果—成本语义层。
 *
 * <p>本服务只解释真实事实，不推断用户是否真的解决问题。auto-resolved 明确标记为代理指标；金额因现有
 * 账单粒度无法关联会话而返回 unavailable，禁止按调用数或 token 比例伪分摊。</p>
 */
@Service
public class BusinessOutcomeService {

    private static final int RATIO_SCALE = 6;
    private static final long MAX_WINDOW_MS = 90L * 24 * 60 * 60 * 1000;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String DATA_SOURCE = "CUSTOMER_WORK_DATABASE";
    private static final String NO_SESSION_COST_REASON =
        "现有金额事实仅按租户、自然日和模型归集，无法无损关联到 session；未进行比例分摊";
    private static final BusinessOutcomeDefinitions DEFINITIONS = new BusinessOutcomeDefinitions(
        "session_id 非空，且窗口内至少有一条调用日志；窗口按调用 start_time 左闭右开",
        "窗口内该 session 的调用 success 均为 true；这是技术成功，不等同业务问题已解决",
        "技术成功且未发现 cw_handoff_ticket 或带转人工事实的 cw_ticket；仅为自动解决代理指标",
        "同 session 存在 cw_handoff_ticket，或 cw_ticket 已记录 handoff_at_ms/handoff_reason",
        "仅关联同租户同 session 的 cw_csat_survey；满意定义为已提交评分 >= 4",
        "汇总窗口内调用日志 total_tokens；缺失调用通过 availability 显式披露",
        NO_SESSION_COST_REASON
    );

    private final BusinessOutcomeGatewayProvider gatewayProvider;
    private final Clock clock;

    @Autowired
    public BusinessOutcomeService(BusinessOutcomeGatewayProvider gatewayProvider) {
        this(gatewayProvider, Clock.systemUTC());
    }

    BusinessOutcomeService(BusinessOutcomeGatewayProvider gatewayProvider, Clock clock) {
        this.gatewayProvider = gatewayProvider;
        this.clock = clock;
    }

    public BusinessOutcomeSummaryVO summary(long fromMs, long toMs, String agentCode) {
        QueryScope scope = scope(fromMs, toMs, agentCode);
        BusinessOutcomeAggregateRow row = mapper().aggregate(scope.tenantId(), scope.agentCode(),
            fromMs, toMs);
        BusinessOutcomeAggregateRow aggregate = row == null ? new BusinessOutcomeAggregateRow() : row;
        long sessions = value(aggregate.getTotalSessions());
        long successful = value(aggregate.getSuccessfulSessions());
        long autoResolved = value(aggregate.getAutoResolvedProxySessions());
        long handoffs = value(aggregate.getHandoffSessions());
        long totalCalls = value(aggregate.getTotalCalls());
        long knownTokenCalls = value(aggregate.getKnownTokenCalls());
        long unknownTokenCalls = value(aggregate.getUnknownTokenCalls());
        long responded = value(aggregate.getCsatRespondedSessions());
        MetricAvailability tokenAvailability = tokenAvailability(totalCalls, knownTokenCalls,
            unknownTokenCalls);
        Long totalTokens = knownTokenCalls == 0 ? null : value(aggregate.getKnownTotalTokens());
        MetricAvailability costAvailability = new MetricAvailability("UNAVAILABLE",
            NO_SESSION_COST_REASON);
        return new BusinessOutcomeSummaryVO(scope.tenantId(), scope.agentCode(), fromMs, toMs,
            clock.millis(), DATA_SOURCE, sessions, successful, ratio(successful, sessions),
            autoResolved, ratio(autoResolved, sessions), handoffs, ratio(handoffs, sessions),
            totalCalls, totalTokens, tokenAvailability,
            value(aggregate.getCsatInvitedSessions()), responded,
            ratio(responded, value(aggregate.getCsatInvitedSessions())),
            scale(aggregate.getAverageCsat()),
            ratio(value(aggregate.getCsatSatisfiedSessions()), responded),
            null, null, costAvailability, DEFINITIONS);
    }

    public BusinessOutcomeSessionPageVO sessions(long fromMs, long toMs, String agentCode,
                                                   int page, int size) {
        QueryScope scope = scope(fromMs, toMs, agentCode);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BizException(ResultCode.PARAM_INVALID, "页码必须大于 0，分页大小范围为 1-200");
        }
        BusinessOutcomeMapper mapper = mapper();
        long total = mapper.countSessions(scope.tenantId(), scope.agentCode(), fromMs, toMs);
        long offset = (long) (page - 1) * size;
        List<BusinessOutcomeSessionVO> records = mapper.findSessions(scope.tenantId(), scope.agentCode(),
                fromMs, toMs, offset, size).stream()
            .map(BusinessOutcomeService::toSessionVO)
            .toList();
        return new BusinessOutcomeSessionPageVO(total, page, size, records);
    }

    private BusinessOutcomeMapper mapper() {
        return gatewayProvider.get().mapper();
    }

    private QueryScope scope(long fromMs, long toMs, String rawAgentCode) {
        if (fromMs < 0 || toMs <= fromMs) {
            throw new BizException(ResultCode.PARAM_INVALID, "统计窗口必须是有效的左闭右开毫秒区间");
        }
        if (toMs - fromMs > MAX_WINDOW_MS) {
            throw new BizException(ResultCode.PARAM_INVALID, "单次统计窗口不能超过 90 天");
        }
        String tenantId = TenantSession.effectiveTenant();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文");
        }
        String agentCode = rawAgentCode == null || rawAgentCode.isBlank() ? null : rawAgentCode.trim();
        if (agentCode != null && agentCode.length() > 128) {
            throw new BizException(ResultCode.PARAM_INVALID, "Agent 编码不能超过 128 个字符");
        }
        return new QueryScope(tenantId, agentCode);
    }

    private static BusinessOutcomeSessionVO toSessionVO(BusinessOutcomeSessionRow row) {
        long failedCalls = value(row.getFailedCalls());
        boolean successful = failedCalls == 0;
        boolean handedOff = Boolean.TRUE.equals(row.getHandedOff());
        long known = value(row.getKnownTokenCalls());
        long unknown = value(row.getUnknownTokenCalls());
        long calls = value(row.getCallCount());
        return new BusinessOutcomeSessionVO(row.getSessionId(), row.getAgentCodes(),
            value(row.getFirstCallAtMs()), value(row.getLastCallAtMs()), calls, successful,
            handedOff, successful && !handedOff,
            known == 0 ? null : value(row.getKnownTotalTokens()),
            tokenAvailability(calls, known, unknown), row.getCsatScore());
    }

    private static MetricAvailability tokenAvailability(long totalCalls, long knownCalls,
                                                        long unknownCalls) {
        if (totalCalls == 0) {
            return new MetricAvailability("UNAVAILABLE", "观测窗口内没有调用");
        }
        if (knownCalls == 0) {
            return new MetricAvailability("UNAVAILABLE", "所有调用均缺失 total_tokens");
        }
        if (unknownCalls > 0) {
            return new MetricAvailability("PARTIAL",
                "部分调用缺失 total_tokens；返回值仅为已上报调用之和");
        }
        return new MetricAvailability("COMPLETE", "窗口内全部调用均上报 total_tokens");
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), RATIO_SCALE,
            RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private record QueryScope(String tenantId, String agentCode) {
    }
}
