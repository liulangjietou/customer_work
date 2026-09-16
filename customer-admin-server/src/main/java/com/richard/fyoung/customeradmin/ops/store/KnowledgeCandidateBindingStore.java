package com.richard.fyoung.customeradmin.ops.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 不可变绑定与父改进记录使用同一 Admin 事务；读取始终带精确租户、父记录及指纹。 */
@Repository
public class KnowledgeCandidateBindingStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public KnowledgeCandidateBindingStore(DataSource source, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(source);
        this.objectMapper = objectMapper;
    }

    /** 重复提交相同冻结输入只回读原绑定，不覆盖历史内容和实际操作人。 */
    public KnowledgeCandidateBinding save(KnowledgeCandidateBinding binding, long actor) {
        String tenant = binding.knowledge().tenantId();
        try {
            jdbc.update("INSERT INTO ai_knowledge_candidate_binding(tenant_id,improvement_id,artifact_fingerprint,"
                    + "candidate_id,candidate_revision,input_json,created_by,created_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                tenant, binding.improvementId(), binding.fingerprint(), binding.knowledge().candidateId(),
                binding.knowledge().candidateRevision(), write(binding), actor, System.currentTimeMillis());
            return binding;
        } catch (DuplicateKeyException duplicate) {
            var stored = find(tenant, binding.improvementId(), binding.fingerprint()).orElseThrow(() -> duplicate);
            if (!stored.equals(binding)) throw new IllegalStateException("knowledge binding fingerprint collision", duplicate);
            return stored;
        }
    }

    /** 缺失与其它租户不可见统一返回空，损坏的证据抛出错误而不是降级成不存在。 */
    public Optional<KnowledgeCandidateBinding> find(String tenant, long improvementId, String fingerprint) {
        return jdbc.query("SELECT input_json FROM ai_knowledge_candidate_binding "
                + "WHERE tenant_id=? AND improvement_id=? AND artifact_fingerprint=?",
            (row, index) -> read(row.getString(1), tenant, improvementId, fingerprint),
            tenant, improvementId, fingerprint).stream().findFirst();
    }

    private String write(KnowledgeCandidateBinding binding) {
        try { return objectMapper.writeValueAsString(binding); }
        catch (JsonProcessingException e) { throw new IllegalStateException("failed to serialize knowledge binding", e); }
    }

    private KnowledgeCandidateBinding read(String value, String tenant, long improvementId, String fingerprint) {
        try {
            var binding = objectMapper.readValue(value, KnowledgeCandidateBinding.class);
            if (binding.improvementId() != improvementId || !tenant.equals(binding.knowledge().tenantId())
                || !fingerprint.equals(binding.fingerprint()) || !binding.dataset().isContentIntact()) {
                throw new IllegalStateException("knowledge binding identity or content mismatch");
            }
            return binding;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read knowledge binding", e);
        }
    }
}
