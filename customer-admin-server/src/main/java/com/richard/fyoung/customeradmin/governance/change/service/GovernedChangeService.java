package com.richard.fyoung.customeradmin.governance.change.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.governance.change.GovernanceProperties;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeStatus;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeType;
import com.richard.fyoung.customeradmin.governance.change.dto.GovernanceAuditEventVO;
import com.richard.fyoung.customeradmin.governance.change.dto.GovernedChangeVO;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernanceAuditEvent;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernanceAuditEventMapper;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernedChangeRequestMapper;
import com.richard.fyoung.customeradmin.governance.change.service.ConfigRollbackGovernedChangeExecutor.ConfigGrayReleaseCommand;
import com.richard.fyoung.customeradmin.governance.change.service.ConfigRollbackGovernedChangeExecutor.ConfigRollbackCommand;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** maker 提交、checker 决策与类型化执行的应用服务。 */
@Service
public class GovernedChangeService {

    private static final int LIST_LIMIT = 100;
    private static final String EXECUTION_FAILURE_CODE = "GOVERNED_CHANGE_EXECUTION_FAILED";

    private final GovernedChangeRequestMapper requestMapper;
    private final GovernanceAuditEventMapper auditMapper;
    private final GovernedChangeStateService stateService;
    private final GovernanceProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<GovernedChangeType, GovernedChangeExecutor> executors;

    public GovernedChangeService(GovernedChangeRequestMapper requestMapper,
                                 GovernanceAuditEventMapper auditMapper,
                                 GovernedChangeStateService stateService,
                                 GovernanceProperties properties,
                                 ObjectMapper objectMapper,
                                 List<GovernedChangeExecutor> executorList) {
        this.requestMapper = requestMapper;
        this.auditMapper = auditMapper;
        this.stateService = stateService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executors = buildExecutors(executorList);
    }

    public GovernedChangeVO submitRollback(Long versionId, String remark,
                                           Long makerId, String makerName) {
        return submit(GovernedChangeType.CONFIG_ROLLBACK, "config-version:" + versionId,
            new ConfigRollbackCommand(versionId, remark), makerId, makerName);
    }

    public GovernedChangeVO submitGrayRelease(Long versionId, List<String> tenantCodes,
                                              String remark, Long makerId, String makerName) {
        return submit(GovernedChangeType.CONFIG_GRAY_RELEASE, "config-version:" + versionId,
            new ConfigGrayReleaseCommand(versionId, List.copyOf(tenantCodes), remark),
            makerId, makerName);
    }

    public List<GovernedChangeVO> list(String status) {
        LambdaQueryWrapper<AiGovernedChangeRequest> query =
            new LambdaQueryWrapper<AiGovernedChangeRequest>()
                .orderByDesc(AiGovernedChangeRequest::getCreateTime)
                .last("LIMIT " + LIST_LIMIT);
        if (StringUtils.hasText(status)) {
            GovernedChangeStatus parsed;
            try {
                parsed = GovernedChangeStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BizException(ResultCode.PARAM_INVALID, "未知审批状态");
            }
            query.eq(AiGovernedChangeRequest::getStatus, parsed.name());
        }
        return requestMapper.selectList(query).stream().map(GovernedChangeVO::from).toList();
    }

    public List<GovernanceAuditEventVO> audit(String requestId) {
        requireCurrentTenantRequest(requestId);
        return auditMapper.selectList(new LambdaQueryWrapper<AiGovernanceAuditEvent>()
                .eq(AiGovernanceAuditEvent::getRequestId, requestId)
                .orderByAsc(AiGovernanceAuditEvent::getSequenceNo))
            .stream().map(GovernanceAuditEventVO::from).toList();
    }

    public GovernedChangeVO approve(String id, Long checkerId, String checkerName, String reason) {
        String tenantId = TenantContext.require();
        AiGovernedChangeRequest claimed = stateService.claim(
            id, tenantId, checkerId, checkerName, reason);
        GovernedChangeType type = GovernedChangeType.valueOf(claimed.getChangeType());
        GovernedChangeExecutor executor = executors.get(type);
        if (executor == null) {
            stateService.fail(id, tenantId, "GOVERNED_CHANGE_EXECUTOR_MISSING");
            throw new IllegalStateException("governed change executor is missing: " + type);
        }
        try {
            Object result = executor.execute(claimed);
            return GovernedChangeVO.from(stateService.complete(
                id, tenantId, objectMapper.writeValueAsString(result)));
        } catch (RuntimeException e) {
            stateService.fail(id, tenantId, EXECUTION_FAILURE_CODE);
            throw e;
        } catch (Exception e) {
            stateService.fail(id, tenantId, EXECUTION_FAILURE_CODE);
            throw new IllegalStateException("governed change result serialization failed", e);
        }
    }

    public GovernedChangeVO reject(String id, Long checkerId, String checkerName, String reason) {
        return GovernedChangeVO.from(stateService.reject(
            id, TenantContext.require(), checkerId, checkerName, reason));
    }

    private GovernedChangeVO submit(GovernedChangeType type, String targetKey, Object payload,
                                    Long makerId, String makerName) {
        if (makerId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String tenantId = TenantContext.require();
        String payloadJson = writeJson(payload);
        String payloadHash = sha256(payloadJson);
        long duplicate = requestMapper.selectCount(new LambdaQueryWrapper<AiGovernedChangeRequest>()
            .eq(AiGovernedChangeRequest::getChangeType, type.name())
            .eq(AiGovernedChangeRequest::getTargetKey, targetKey)
            .eq(AiGovernedChangeRequest::getPayloadHash, payloadHash)
            .eq(AiGovernedChangeRequest::getMakerId, makerId)
            .eq(AiGovernedChangeRequest::getStatus, GovernedChangeStatus.PENDING.name())
            .gt(AiGovernedChangeRequest::getExpiresAt, LocalDateTime.now()));
        if (duplicate > 0) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "相同变更已有待审批请求");
        }

        LocalDateTime now = LocalDateTime.now();
        AiGovernedChangeRequest request = new AiGovernedChangeRequest();
        request.setId(UUID.randomUUID().toString());
        request.setTenantId(tenantId);
        request.setChangeType(type.name());
        request.setTargetKey(targetKey);
        request.setPayloadJson(payloadJson);
        request.setPayloadHash(payloadHash);
        request.setMakerId(makerId);
        request.setMakerName(makerName);
        request.setStatus(GovernedChangeStatus.PENDING.name());
        request.setExpiresAt(now.plusHours(properties.effectiveApprovalExpiryHours()));
        request.setCreateTime(now);
        request.setUpdateTime(now);
        return GovernedChangeVO.from(stateService.create(request));
    }

    private AiGovernedChangeRequest requireCurrentTenantRequest(String id) {
        AiGovernedChangeRequest request = requestMapper.selectById(id);
        if (request == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "审批请求不存在");
        }
        return request;
    }

    private Map<GovernedChangeType, GovernedChangeExecutor> buildExecutors(
        List<GovernedChangeExecutor> executorList) {
        Map<GovernedChangeType, GovernedChangeExecutor> result =
            new EnumMap<>(GovernedChangeType.class);
        for (GovernedChangeExecutor executor : executorList) {
            for (GovernedChangeType type : executor.types()) {
                if (result.putIfAbsent(type, executor) != null) {
                    throw new IllegalStateException("duplicate governed change executor: " + type);
                }
            }
        }
        return Map.copyOf(result);
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "审批请求序列化失败");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
