package com.richard.fyoung.customerwork.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.memory.entity.MemoryConsentDO;
import com.richard.fyoung.customerwork.core.memory.mapper.MemoryConsentMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

import java.util.Optional;

/** MyBatis 长期记忆同意存储。写失败必须抛出，不能把“未持久化”伪装成已授权或已撤回。 */
public class MybatisMemoryConsentStore implements MemoryConsentStore {

    private final MemoryConsentMapper mapper;

    public MybatisMemoryConsentStore(MemoryConsentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<MemoryConsent> find(MemorySubjectKey subject) {
        requireSubjectTenant(subject);
        MemoryConsentDO row = mapper.selectOne(new LambdaQueryWrapper<MemoryConsentDO>()
            .eq(MemoryConsentDO::getSubjectType, subject.subjectType().name())
            .eq(MemoryConsentDO::getSubjectId, subject.subjectId())
            .eq(MemoryConsentDO::getAgentId, subject.agentId()));
        return Optional.ofNullable(row).map(value -> toDomain(subject, value));
    }

    @Override
    public void save(MemoryConsent consent) {
        requireSubjectTenant(consent.subject());
        if (mapper.upsert(toRecord(consent)) <= 0) {
            throw new IllegalStateException("memory consent was not persisted");
        }
    }

    private MemoryConsentDO toRecord(MemoryConsent consent) {
        MemoryConsentDO row = new MemoryConsentDO();
        row.setSubjectType(consent.subject().subjectType().name());
        row.setSubjectId(consent.subject().subjectId());
        row.setAgentId(consent.subject().agentId());
        row.setScopeId(consent.subject().scopeId());
        row.setStatus(consent.status().name());
        row.setConsentVersion(consent.consentVersion());
        row.setGrantedAtMs(consent.grantedAtMs());
        row.setWithdrawnAtMs(consent.withdrawnAtMs());
        row.setUpdatedAtMs(consent.updatedAtMs());
        return row;
    }

    private MemoryConsent toDomain(MemorySubjectKey subject, MemoryConsentDO row) {
        if (!subject.scopeId().equals(row.getScopeId())) {
            throw new IllegalStateException("memory consent subject scope mismatch");
        }
        return new MemoryConsent(subject, MemoryConsentStatus.valueOf(row.getStatus()),
            row.getConsentVersion(), row.getGrantedAtMs(), row.getWithdrawnAtMs(), row.getUpdatedAtMs());
    }

    private void requireSubjectTenant(MemorySubjectKey subject) {
        if (!TenantContext.sameTenant(subject.tenantId(), TenantContext.require())) {
            throw new IllegalArgumentException("memory consent tenant does not match current context");
        }
    }
}
