package com.richard.fyoung.customeradmin.configversion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishIntent;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService.SafePublishCommand;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService.SafePublishTarget;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigPublishOperationResult;
import com.richard.fyoung.customeradmin.configversion.entity.AiConfigVersion;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 配置安全回滚与灰度发布。
 *
 * <p>历史快照只提供 {@code systemPrompt}/{@code agent.maxIters} 行为补丁。模型、凭据、MCP、
 * 路由与在线实验始终从目标租户当前权威数据重组，并通过可靠任务、Eval 门禁与实例 ACK 生效。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class ConfigRollbackService {

    private final ConfigVersionService versionService;
    private final CustomerWorkConfigPublisher publisher;
    private final RuntimePublishTaskService taskService;
    private final TenantService tenantService;
    private final RuntimeRollbackPatchExtractor patchExtractor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigRollbackService(ConfigVersionService versionService,
                                 CustomerWorkConfigPublisher publisher,
                                 RuntimePublishTaskService taskService,
                                 TenantService tenantService,
                                 RuntimeRollbackPatchExtractor patchExtractor) {
        this.versionService = versionService;
        this.publisher = publisher;
        this.taskService = taskService;
        this.tenantService = tenantService;
        this.patchExtractor = patchExtractor;
    }

    /**
     * 回滚到指定版本：提取历史行为补丁，以当前租户权威配置重组候选并可靠入队。
     *
     * @param versionId 目标版本主键
     * @param remark    回滚说明（建议写清为什么回滚，事后翻历史时这句话最有用）
     * @return PENDING 可靠任务；只有后续 ACK APPLIED 才表示实例真实生效
     */
    public ConfigPublishOperationResult rollback(Long versionId, String remark) {
        assertPublishEnabled();
        AiConfigVersion source = requireSafeSource(versionId);
        String effectiveRemark = remark == null || remark.isBlank()
            ? "安全回滚至 v" + source.getVersion() : remark;
        return enqueue(source, List.of(TenantContext.require()), RuntimePublishIntent.SAFE_ROLLBACK,
            effectiveRemark, null);
    }

    /**
     * 灰度发布：逐目标租户重组当前权威配置，仅应用历史行为补丁后可靠入队。
     *
     * <p>逐租户写各自的 dataId（{@code <主dataId>-tenant-<租户码>}）。客服端按自己的租户读，
     * 读不到就回落主 dataId——因此名单外的租户继续用全量版本，客服端不需要理解"灰度"这个概念。</p>
     *
     * @return 同一 operation 下的 PENDING 可靠任务列表
     */
    public ConfigPublishOperationResult grayRelease(Long versionId, List<String> tenantCodes,
                                                    String remark) {
        assertPublishEnabled();
        if (CollectionUtils.isEmpty(tenantCodes)) {
            throw new BizException(ResultCode.PARAM_MISSING, "灰度发布必须指定至少一个租户");
        }
        AiConfigVersion source = requireSafeSource(versionId);
        List<String> resolvedTenantCodes = resolveGrayTenantCodes(tenantCodes);
        String grayTenantsJson = serializeTenants(resolvedTenantCodes);
        String effectiveRemark = remark == null || remark.isBlank()
            ? "安全灰度自 v" + source.getVersion() : remark;
        return enqueue(source, resolvedTenantCodes, RuntimePublishIntent.SAFE_GRAY,
            effectiveRemark, grayTenantsJson);
    }

    private ConfigPublishOperationResult enqueue(AiConfigVersion source,
                                                 List<String> tenantCodes,
                                                 RuntimePublishIntent intent,
                                                 String remark,
                                                 String grayTenantsJson) {
        RuntimeRollbackPatch patch = patchExtractor.extract(source.getContent());
        String sourceHash = patchExtractor.verifyContentHash(
            source.getContent(), source.getContentHash());
        String patchJson = patchExtractor.serialize(patch);

        // 先完成全部租户的当前配置重组与连通性预检；此阶段绝不落任务，任一失败即整批为零。
        List<SafePublishTarget> targets = new ArrayList<>();
        for (String tenantCode : tenantCodes) {
            try {
                Long agentId = TenantContext.callWith(tenantCode, () ->
                    publisher.validateSafePublishCandidate(source.getTargetCode(), patchJson, intent));
                targets.add(new SafePublishTarget(tenantCode, agentId));
            } catch (Exception e) {
                log.error("safe runtime publish preflight failed, code={}, tenant={}, target={}",
                    "CONFIG-SAFE-PUBLISH-PREFLIGHT-FAIL", tenantCode, source.getTargetCode(), e);
                throw new BizException(ResultCode.RUNTIME_PUBLISH_FAILED,
                    "目标租户当前运行配置预校验失败，未创建任何发布任务: " + tenantCode);
            }
        }

        String operationId = UUID.randomUUID().toString();
        Integer sourceVersion = intent == RuntimePublishIntent.SAFE_ROLLBACK
            ? source.getVersion() : null;
        List<RuntimePublishTask> tasks;
        try {
            tasks = taskService.enqueueSafe(new SafePublishCommand(
                operationId, intent, source.getId(), sourceHash, patchJson, sourceVersion,
                remark, grayTenantsJson, targets));
        } catch (Exception e) {
            log.error("safe runtime publish enqueue failed, code={}, operationId={}, target={}",
                "CONFIG-SAFE-PUBLISH-ENQUEUE-FAIL", operationId, source.getTargetCode(), e);
            throw new BizException(ResultCode.RUNTIME_PUBLISH_FAILED,
                "安全发布任务入队失败，整批任务已回滚");
        }
        log.info("safe runtime publish queued, operationId={}, intent={}, target={}, tasks={}",
            operationId, intent, source.getTargetCode(), tasks.size());
        return result(operationId, intent, source, sourceHash, tasks);
    }

    private AiConfigVersion requireSafeSource(Long versionId) {
        AiConfigVersion source = versionService.requireVersion(versionId);
        if (!ConfigType.AGENT.name().equals(source.getConfigType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "仅智能体运行时配置支持安全回滚");
        }
        if (source.getContent() == null || source.getContent().isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "该版本没有可回滚的内容快照");
        }
        if ("FAILED".equals(source.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "发布失败的候选不能作为回滚来源");
        }
        return source;
    }

    private ConfigPublishOperationResult result(String operationId,
                                                RuntimePublishIntent intent,
                                                AiConfigVersion source,
                                                String sourceHash,
                                                List<RuntimePublishTask> tasks) {
        List<ConfigPublishOperationResult.PendingTask> pendingTasks = tasks.stream()
            .map(task -> new ConfigPublishOperationResult.PendingTask(
                task.getId(), task.getTenantId(), task.getTargetId(), task.getStatus()))
            .toList();
        return new ConfigPublishOperationResult(operationId, intent.name(),
            RuntimePublishStatus.PENDING.name(), source.getId(), sourceHash, pendingTasks);
    }

    /**
     * 灰度目标必须先解析为租户主数据中的权威编码，禁止把历史兼容值或大小写别名写回外部命名空间。
     */
    private List<String> resolveGrayTenantCodes(List<String> tenantCodes) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String tenantCode : tenantCodes) {
            if (tenantCode == null || tenantCode.isBlank()) {
                throw new BizException(ResultCode.PARAM_INVALID, "灰度租户编码不能为空");
            }
            String authoritativeCode = tenantService.resolveAccessibleCode(tenantCode.trim());
            if (authoritativeCode == null) {
                throw new BizException(ResultCode.TENANT_NOT_FOUND, "灰度租户不存在或不可用");
            }
            resolved.add(authoritativeCode);
        }
        return List.copyOf(resolved);
    }

    private String serializeTenants(List<String> tenantCodes) {
        try {
            return objectMapper.writeValueAsString(tenantCodes);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "灰度租户列表序列化失败");
        }
    }

    private void assertPublishEnabled() {
        if (!publisher.isEnabled()) {
            throw new BizException(ResultCode.RUNTIME_PUBLISH_DISABLED);
        }
    }
}
