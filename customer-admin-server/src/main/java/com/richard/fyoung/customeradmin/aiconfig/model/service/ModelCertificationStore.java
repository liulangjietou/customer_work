package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertificationRun;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationRunMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 认证运行与快照的原子落库入口。 */
@Component
public class ModelCertificationStore {

    private final AiModelCertificationRunMapper runMapper;
    private final AiModelCertificationMapper certificationMapper;

    public ModelCertificationStore(AiModelCertificationRunMapper runMapper,
                                   AiModelCertificationMapper certificationMapper) {
        this.runMapper = runMapper;
        this.certificationMapper = certificationMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public RecordResult record(AiModelCertificationRun run,
                               int passedChecks,
                               int failedChecks,
                               Long secretRefId) {
        CrossTenantOperations.run(() -> runMapper.insert(run));
        AiModelCertification snapshot = new AiModelCertification();
        snapshot.setModelConfigId(run.getModelConfigId());
        snapshot.setTenantId(run.getTenantId());
        snapshot.setStatus(run.getStatus());
        snapshot.setCurrentRunId(run.getId());
        snapshot.setCertifiedEndpointRevision(run.getEndpointRevision());
        snapshot.setCertifiedSecretVersion(run.getSecretVersion());
        snapshot.setValidUntil(run.getValidUntil());
        snapshot.setCompletedAt(run.getCompletedAt());
        snapshot.setPassedChecks(passedChecks);
        snapshot.setFailedChecks(failedChecks);
        snapshot.setLatencyP95Ms(run.getLatencyP95Ms());
        snapshot.setVerifiedContextTokens(run.getVerifiedContextTokens());
        snapshot.setFailureCode(run.getFailureCode());
        snapshot.setFailureMessage(run.getFailureMessage());
        snapshot.setRevision(1);
        CrossTenantOperations.run(() -> certificationMapper.promoteIfCurrent(snapshot, secretRefId));
        AiModelCertification current = CrossTenantOperations.execute(() ->
            certificationMapper.selectById(run.getModelConfigId()));
        return new RecordResult(run, current != null
            && Objects.equals(run.getId(), current.getCurrentRunId()));
    }

    public record RecordResult(AiModelCertificationRun run, boolean promoted) {
    }
}
