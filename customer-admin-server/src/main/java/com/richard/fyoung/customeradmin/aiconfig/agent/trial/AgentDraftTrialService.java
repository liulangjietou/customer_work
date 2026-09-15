package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.dto.AgentDraftVO;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 短事务先受理、事务外执行、条件写入终态；任何恢复入口都不能再次执行模型。 */
@Service
public class AgentDraftTrialService {
    private static final Logger log = LoggerFactory.getLogger(AgentDraftTrialService.class);
    private static final String QUOTA_PATH = "/api/aiconfig/agent-drafts/trials";
    private final AgentDraftTrialStore store;
    private final AgentDraftTrialFreezer freezer;
    private final AgentDraftTrialRunner runner;
    private final SubjectQuotaGuard quota;
    private final ObjectMapper json;

    public AgentDraftTrialService(AgentDraftTrialStore store, AgentDraftTrialFreezer freezer,
        AgentDraftTrialRunner runner, @Qualifier("adminSubjectQuotaGuard") SubjectQuotaGuard quota, ObjectMapper json) {
        this.store = store;
        this.freezer = freezer;
        this.runner = runner;
        this.quota = quota;
        this.json = json;
    }

    /** 只读预检显示即将执行的实际模型、版本与限制，不调用模型、不消耗请求额度。 */
    public Preview preview(AgentDraftVO draft) {
        var snapshot = freezer.freeze(draft);
        return new Preview(draft.version(), freezer.fingerprint(snapshot), snapshot.models().stream()
            .map(model -> model.deploymentId() + ": " + model.model()).toList(),
            snapshot.skills(), snapshot.knowledgeBases(), snapshot.restrictions());
    }

    /** 相同标识相同请求只恢复已有记录，配置后来变化也不能获得第二次执行权。 */
    public AgentDraftTrialView start(AgentDraftVO draft, long ownerId, String trialId,
        AgentDraftTrialRequest request, AgentInvocationIdentity identity) {
        var scope = scope(draft.id(), ownerId, trialId);
        var existing = store.find(scope);
        if (existing.isPresent()) return recover(draft, existing.get(), request);
        if (draft.version() != request.expectedDraftVersion()) throw conflict();
        var snapshot = freezer.freeze(draft);
        long now = System.currentTimeMillis();
        var accepted = new AgentDraftTrialRecord(scope, draft.version(), request.input(),
            AgentDraftTrialRecord.fingerprint(scope, request), freezer.fingerprint(snapshot), freezer.encode(snapshot),
            AgentDraftTrialPhase.RUNNING, null, null, now, now + AgentDraftTrialLimits.EXECUTION_TIMEOUT_MILLIS, null);
        if (!store.accept(accepted)) {
            return recover(draft, store.find(scope).orElseThrow(this::conflict), request);
        }

        AgentDraftTrialRunner.TrialResult result;
        try {
            // 仅唯一受理方检查和记录本次请求；查看或重复恢复回执不重复扣请求额度。
            if (quota.isEnabled()) {
                var subject = QuotaSubject.adminUser(String.valueOf(ownerId));
                var decision = quota.check(subject, QUOTA_PATH);
                if (decision.shouldBlock()) throw new BizException(ResultCode.QUOTA_EXCEEDED);
                quota.recordRequest(subject);
            }
            result = runner.run(snapshot, accepted, identity.forInvocation("admin", trialId,
                snapshot.configuration().agentCode()));
        } catch (Exception error) {
            String code = errorCode(error).name();
            log.warn("Agent draft trial failed, errorCode={}, trialId={}, exceptionType={}",
                code, trialId, error.getClass().getSimpleName());
            store.fail(scope, code, System.currentTimeMillis());
            return get(draft, ownerId, trialId);
        }
        // 终态写入异常不能被重新解释成模型失败：成功可能已经提交，客户端用原 ID 查询即可。
        store.succeed(scope, encodeResult(result), System.currentTimeMillis());
        return get(draft, ownerId, trialId);
    }

    /** 查询和超时收敛都不持有执行权；缺失回执不被解释为可以自动重发。 */
    public AgentDraftTrialView get(AgentDraftVO draft, long ownerId, String trialId) {
        expire(draft.id(), ownerId);
        var record = store.find(scope(draft.id(), ownerId, trialId))
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "试用回执尚未找到，请用原标识继续核对"));
        return view(draft, record);
    }

    /** 最近二十条个人试用，历史记录不随当前配置改变而获得通过标记。 */
    public List<AgentDraftTrialSummary> list(AgentDraftVO draft, long ownerId) {
        expire(draft.id(), ownerId);
        return store.list(TenantContext.require(), ownerId, draft.id());
    }

    private AgentDraftTrialView recover(AgentDraftVO draft, AgentDraftTrialRecord record, AgentDraftTrialRequest request) {
        if (!record.matches(request)) throw conflict();
        return get(draft, record.scope().ownerId(), record.scope().trialId());
    }

    private AgentDraftTrialView view(AgentDraftVO draft, AgentDraftTrialRecord record) {
        try {
            var frozen = json.readValue(record.frozenConfiguration(), FrozenAgentDraft.class);
            var result = record.resultJson() == null ? null
                : json.readValue(record.resultJson(), AgentDraftTrialRunner.TrialResult.class);
            return new AgentDraftTrialView(record.scope().trialId(), record.draftVersion(), record.input(),
                record.configurationFingerprint(), match(draft, record), record.phase(), record.errorCode(),
                record.acceptedAtMs(), record.deadlineAtMs(), record.finishedAtMs(), result, frozen.restrictions());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Agent trial receipt deserialization failed", error);
        }
    }

    private AgentDraftTrialMatch match(AgentDraftVO draft, AgentDraftTrialRecord record) {
        if (draft.version() != record.draftVersion()) return AgentDraftTrialMatch.CHANGED;
        try {
            return record.configurationFingerprint().equals(freezer.fingerprint(freezer.freeze(draft)))
                ? AgentDraftTrialMatch.MATCH : AgentDraftTrialMatch.CHANGED;
        } catch (BizException unavailable) {
            return AgentDraftTrialMatch.UNAVAILABLE;
        }
    }

    private AgentDraftTrialError errorCode(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) return AgentDraftTrialError.TRIAL_TIMEOUT;
            if (cause instanceof AgentDraftTrialToolGuard.RestrictedToolException) return AgentDraftTrialError.TRIAL_TOOL_RESTRICTED;
            if (cause instanceof BizException business && business.getResultCode() == ResultCode.QUOTA_EXCEEDED) {
                return AgentDraftTrialError.TRIAL_QUOTA_EXCEEDED;
            }
        }
        return AgentDraftTrialError.TRIAL_EXECUTION_FAILED;
    }

    private String encodeResult(AgentDraftTrialRunner.TrialResult result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Agent trial result serialization failed", error);
        }
    }

    private AgentDraftTrialScope scope(String draftId, long ownerId, String trialId) {
        return new AgentDraftTrialScope(TenantContext.require(), ownerId, draftId, trialId);
    }

    private void expire(String draftId, long ownerId) {
        store.expire(TenantContext.require(), ownerId, draftId, System.currentTimeMillis());
    }

    private BizException conflict() {
        return new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "试用标识或草稿版本已变化，请保留原标识核对回执");
    }

    public record Preview(long draftVersion, String configurationFingerprint, List<String> models,
                          List<FrozenAgentDraft.Resource> skills, List<FrozenAgentDraft.Resource> knowledgeBases,
                          List<String> restrictions) { }
}
