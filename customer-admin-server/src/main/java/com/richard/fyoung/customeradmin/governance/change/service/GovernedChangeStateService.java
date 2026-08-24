package com.richard.fyoung.customeradmin.governance.change.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeStatus;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernedChangeRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 每个状态迁移独立提交，执行宕机时至少保留 EXECUTING 与审批审计。 */
@Service
public class GovernedChangeStateService {

    private final GovernedChangeRequestMapper mapper;
    private final GovernanceAuditWriter auditWriter;

    public GovernedChangeStateService(GovernedChangeRequestMapper mapper,
                                      GovernanceAuditWriter auditWriter) {
        this.mapper = mapper;
        this.auditWriter = auditWriter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AiGovernedChangeRequest create(AiGovernedChangeRequest request) {
        if (mapper.insert(request) != 1) {
            throw new IllegalStateException("governed change request was not persisted");
        }
        auditWriter.append(request, "SUBMITTED", request.getMakerId(), request.getMakerName(),
            request.getTargetKey(), request.getCreateTime());
        return request;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AiGovernedChangeRequest claim(String id, String tenantId, Long checkerId,
                                         String checkerName, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.claimForExecution(id, tenantId, checkerId, checkerName,
            normalizeReason(reason), now);
        AiGovernedChangeRequest request = require(id, tenantId);
        if (changed != 1) {
            throw stateConflict(request, checkerId);
        }
        request = require(id, tenantId);
        auditWriter.append(request, "APPROVED", checkerId, checkerName,
            normalizeReason(reason), now);
        return request;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AiGovernedChangeRequest reject(String id, String tenantId, Long checkerId,
                                          String checkerName, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.rejectPending(id, tenantId, checkerId, checkerName,
            normalizeReason(reason), now);
        AiGovernedChangeRequest request = require(id, tenantId);
        if (changed != 1) {
            throw stateConflict(request, checkerId);
        }
        request = require(id, tenantId);
        auditWriter.append(request, "REJECTED", checkerId, checkerName,
            normalizeReason(reason), now);
        return request;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AiGovernedChangeRequest complete(String id, String tenantId, String resultJson) {
        LocalDateTime now = LocalDateTime.now();
        if (mapper.markExecuted(id, tenantId, resultJson, now) != 1) {
            throw new IllegalStateException("governed change completion state conflict");
        }
        AiGovernedChangeRequest request = require(id, tenantId);
        auditWriter.append(request, "EXECUTED", request.getCheckerId(), request.getCheckerName(),
            "execution completed", now);
        return request;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AiGovernedChangeRequest fail(String id, String tenantId, String failureCode) {
        LocalDateTime now = LocalDateTime.now();
        if (mapper.markFailed(id, tenantId, failureCode, now) != 1) {
            throw new IllegalStateException("governed change failure state conflict");
        }
        AiGovernedChangeRequest request = require(id, tenantId);
        auditWriter.append(request, "FAILED", request.getCheckerId(), request.getCheckerName(),
            failureCode, now);
        return request;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void expire(String id, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        if (mapper.markExpired(id, tenantId, now) == 1) {
            AiGovernedChangeRequest request = require(id, tenantId);
            auditWriter.append(request, "EXPIRED", null, "system", "approval expired", now);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failTimedOutExecution(String id, String tenantId, LocalDateTime cutoff) {
        LocalDateTime now = LocalDateTime.now();
        if (mapper.markExecutionTimedOut(id, tenantId, cutoff, now) == 1) {
            AiGovernedChangeRequest request = require(id, tenantId);
            auditWriter.append(request, "FAILED", null, "system",
                "GOVERNED_CHANGE_EXECUTION_TIMEOUT", now);
        }
    }

    private AiGovernedChangeRequest require(String id, String tenantId) {
        AiGovernedChangeRequest request = mapper.selectById(id);
        if (request == null || !tenantId.equals(request.getTenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "审批请求不存在");
        }
        return request;
    }

    private BizException stateConflict(AiGovernedChangeRequest request, Long checkerId) {
        if (checkerId.equals(request.getMakerId())) {
            return new BizException(ResultCode.FORBIDDEN, "发起人与复核人必须是不同用户");
        }
        if (request.getExpiresAt().isBefore(LocalDateTime.now())) {
            return new BizException(ResultCode.PARAM_INVALID, "审批请求已过期");
        }
        return new BizException(ResultCode.PARAM_INVALID,
            "审批请求状态已变化: " + request.getStatus());
    }

    private String normalizeReason(String reason) {
        return reason == null ? "" : reason.trim();
    }
}
