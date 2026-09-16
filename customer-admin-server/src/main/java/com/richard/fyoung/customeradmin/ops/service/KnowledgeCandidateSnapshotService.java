package com.richard.fyoung.customeradmin.ops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeTrialSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 跨候选、来源与正式 FAQ 聚合冻结输入；不写 FAQ，不复用正式 Agent 的状态或工具授权。 */
@Service
public class KnowledgeCandidateSnapshotService {
    private final KnowledgeCandidateService candidates;
    private final OpsGatewayProvider gateway;
    private final ObjectMapper objectMapper;

    public KnowledgeCandidateSnapshotService(KnowledgeCandidateService candidates, OpsGatewayProvider gateway,
                                             ObjectMapper objectMapper) {
        this.candidates = candidates;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /** 候选虚拟行排在既有条目之后，与新增 FAQ 后按主键排序的召回次序一致。 */
    public KnowledgeTrialSnapshot freeze(String candidateId, long revision) {
        String tenant = TenantContext.require();
        var candidate = candidates.requireCurrentVersion(candidateId, revision);
        var entries = new ArrayList<>(gateway.get().knowledgeMapper().snapshotForTenant(tenant));
        String baselineJson = write(entries);
        String baseline = EvalFingerprint.of("formal-faq-snapshot-v1", tenant, baselineJson);
        long candidateRowId = Math.addExact(entries.stream().mapToLong(KnowledgeDO::getId).max().orElse(0L), 1L);
        var entry = new KnowledgeDO();
        entry.setId(candidateRowId); entry.setTitle(candidate.title()); entry.setContent(candidate.content());
        entry.setKeyword(candidate.keyword());
        entry.setSource("knowledge-candidate/" + candidate.id() + "/v" + candidate.revision());
        entries.add(entry);
        return new KnowledgeTrialSnapshot(tenant, candidate.id(), candidate.revision(),
            candidate.sourceReviewRevision(), candidate.contentHash(), baseline, baselineJson, write(entries), candidateRowId);
    }

    /** 发布前重新核对所有输入；旧答复和高分不能替代当前候选与当前正式知识的一致性。 */
    public void requireUnchanged(KnowledgeTrialSnapshot evaluated) {
        if (!Objects.equals(TenantContext.require(), evaluated.tenantId())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识候选评测不存在");
        }
        var current = freeze(evaluated.candidateId(), evaluated.candidateRevision());
        if (!Objects.equals(evaluated.fingerprint(), current.fingerprint())) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "候选或正式知识已变化，请重新绑定并评测");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize frozen FAQ snapshot", e);
        }
    }
}
