package com.richard.fyoung.customeradmin.ops.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateEvaluation;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 评测事实只追加；原始 JSON 指纹在解析前核验，派生的 EvalRun 字段不参与回读。 */
@Repository
public class KnowledgeCandidateEvaluationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeCandidateEvaluationStore(DataSource source, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        this.mapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 相同运行的响应丢失重试不覆盖审计；UUID 相同但内容不同视为损坏。 */
    public KnowledgeCandidateEvaluation save(KnowledgeCandidateEvaluation evaluation) {
        String json = write(evaluation);
        String hash = hash(json);
        try {
            jdbc.update("INSERT INTO ai_knowledge_candidate_evaluation(tenant_id,run_id,improvement_id,"
                    + "artifact_fingerprint,evaluation_json,content_hash,created_at_ms) VALUES(?,?,?,?,?,?,?)",
                evaluation.tenantId(), evaluation.current().runId(), evaluation.improvementId(),
                evaluation.artifactFingerprint(), json, hash, evaluation.current().createdAtMs());
            return evaluation;
        } catch (DuplicateKeyException duplicate) {
            var existing = find(evaluation.tenantId(), evaluation.improvementId(),
                evaluation.artifactFingerprint(), evaluation.current().runId()).orElseThrow(() -> duplicate);
            if (!existing.equals(evaluation)) throw new IllegalStateException("knowledge evaluation run conflict", duplicate);
            return existing;
        }
    }

    /** 读取同时限定租户、改进项、冻结版本和运行编号，找不到时不退回全局评测记录。 */
    public Optional<KnowledgeCandidateEvaluation> find(String tenant, long improvementId, String fingerprint, String runId) {
        return jdbc.query("SELECT evaluation_json,content_hash FROM ai_knowledge_candidate_evaluation "
                + "WHERE tenant_id=? AND improvement_id=? AND artifact_fingerprint=? AND run_id=?",
            (row, index) -> {
                String json = row.getString(1);
                if (!hash(json).equals(row.getString(2))) throw new IllegalStateException("knowledge evaluation content mismatch");
                var value = read(json);
                if (!tenant.equals(value.tenantId()) || improvementId != value.improvementId()
                    || !fingerprint.equals(value.artifactFingerprint()) || !runId.equals(value.current().runId())) {
                    throw new IllegalStateException("knowledge evaluation identity mismatch");
                }
                return value;
            }, tenant, improvementId, fingerprint, runId).stream().findFirst();
    }

    private String hash(String json) {
        return EvalFingerprint.of("knowledge-candidate-evaluation-v1", json);
    }

    private String write(KnowledgeCandidateEvaluation value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("failed to serialize knowledge evaluation", e); }
    }

    private KnowledgeCandidateEvaluation read(String json) {
        try { return mapper.readValue(json, KnowledgeCandidateEvaluation.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("failed to read knowledge evaluation", e); }
    }
}
