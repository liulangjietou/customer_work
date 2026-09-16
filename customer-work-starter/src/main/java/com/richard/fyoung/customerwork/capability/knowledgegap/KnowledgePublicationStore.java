package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 客服库内核对人工来源、正式语料并原子写入 FAQ 与回执。
 *
 * <p>Admin 负责评测和发布意图，跨库不能共享事务。本存储把真正的业务副作用和幂等事实放在同库，
 * Admin 提交结果未知时可按原任务重放。查询显式精确匹配租户，不依赖租户插件。</p>
 */
public class KnowledgePublicationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public KnowledgePublicationStore(DataSource source, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(source);
        this.json = json;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 正式语料的范围锁还须挡住不经过此存储的旧 FAQ 插入；READ_COMMITTED 不保留必要的间隙锁。
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(30);
    }

    /** 先核对已提交回执；发布后的正式语料必然变化，重放不得先用旧基线拒绝自己的成功结果。 */
    public Optional<KnowledgePublicationReceipt> find(KnowledgePublicationCommand command) {
        return receipt(TenantContext.require(), command);
    }

    /** 不覆盖正式条目；相同任务返回第一次的实际主键，不同任务不能重复发布同一候选修订。 */
    public KnowledgePublicationReceipt publish(KnowledgePublicationCommand command) {
        String tenant = TenantContext.require();
        try {
            return publishTransaction(tenant, command);
        } catch (DuplicateKeyException duplicate) {
            // 此时事务已回滚，唯一约束冲突是确定的未发布，不能让 Worker 当作未知结果不断重试。
            throw conflict("知识标题或候选修订已有正式记录，请核对后重新绑定并评测");
        }
    }

    private KnowledgePublicationReceipt publishTransaction(String tenant, KnowledgePublicationCommand command) {
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO cw_knowledge_publication_lock(tenant_id) VALUES(?) "
                + "ON DUPLICATE KEY UPDATE tenant_id=tenant_id", tenant);
            Optional<KnowledgePublicationReceipt> existing = receipt(tenant, command);
            if (existing.isPresent()) return existing.get();
            if (!jdbc.queryForList("SELECT task_id FROM cw_knowledge_publication WHERE tenant_id=? "
                    + "AND candidate_id=? AND candidate_revision=?", String.class,
                tenant, command.candidateId(), command.candidateRevision()).isEmpty()) {
                throw conflict("该候选修订已有发布回执，请核对原发布任务");
            }
            requireSource(tenant, command);
            // 锁住当前租户所用的行与范围；旧 badcase 直接插入也不能穿过核对与写入之间的窗口。
            List<KnowledgeDO> current = jdbc.query("SELECT id,keyword,title,content,source FROM cw_knowledge "
                + "WHERE BINARY tenant_id=BINARY ? ORDER BY id ASC FOR UPDATE", (row, index) -> {
                    var entry = new KnowledgeDO();
                    entry.setId(row.getLong("id")); entry.setKeyword(row.getString("keyword"));
                    entry.setTitle(row.getString("title")); entry.setContent(row.getString("content"));
                    entry.setSource(row.getString("source")); return entry;
                }, tenant);
            if (!current.equals(baseline(command))) throw conflict("正式知识已变化，请重新绑定并评测");
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement insert = connection.prepareStatement("INSERT INTO cw_knowledge"
                    + "(tenant_id,title,content,keyword,source) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                insert.setString(1, tenant); insert.setString(2, command.title());
                insert.setString(3, command.content()); insert.setString(4, command.keyword());
                insert.setString(5, command.source()); return insert;
            }, key);
            long knowledgeId = Objects.requireNonNull(key.getKey(), "inserted FAQ id is missing").longValue();
            long now = System.currentTimeMillis();
            String fingerprint = command.fingerprint(tenant);
            jdbc.update("INSERT INTO cw_knowledge_publication(tenant_id,task_id,improvement_id,candidate_id,"
                    + "candidate_revision,artifact_fingerprint,evaluation_run_id,question_hash,source_review_revision,"
                    + "command_fingerprint,knowledge_id,requested_by,published_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenant, command.taskId(), command.improvementId(), command.candidateId(), command.candidateRevision(),
                command.artifactFingerprint(), command.evaluationRunId(), command.questionHash(),
                command.sourceReviewRevision(), fingerprint, knowledgeId, command.requestedBy(), now);
            return new KnowledgePublicationReceipt(command.taskId(), fingerprint, knowledgeId, now);
        });
    }

    private Optional<KnowledgePublicationReceipt> receipt(String tenant, KnowledgePublicationCommand command) {
        var receipts = jdbc.query("SELECT task_id,command_fingerprint,knowledge_id,published_at_ms "
            + "FROM cw_knowledge_publication WHERE tenant_id=? AND task_id=?", (row, index) ->
                new KnowledgePublicationReceipt(row.getString("task_id"), row.getString("command_fingerprint"),
                    row.getLong("knowledge_id"), row.getLong("published_at_ms")), tenant, command.taskId());
        if (receipts.isEmpty()) return Optional.empty();
        var receipt = receipts.get(0);
        if (!receipt.commandFingerprint().equals(command.fingerprint(tenant))) {
            throw conflict("发布任务与原始意图不一致，不能沿用已有回执");
        }
        return Optional.of(receipt);
    }

    private void requireSource(String tenant, KnowledgePublicationCommand command) {
        var allowed = jdbc.query("SELECT category,classification_origin,review_revision FROM cw_knowledge_gap "
            + "WHERE BINARY tenant_id=BINARY ? AND BINARY scope_id=BINARY ? AND BINARY question_hash=BINARY ? "
            + "FOR UPDATE", (row, index) -> "KNOWLEDGE".equals(row.getString("category"))
                && "MANUAL".equals(row.getString("classification_origin"))
                && command.sourceReviewRevision() == row.getLong("review_revision"), tenant, tenant, command.questionHash());
        if (allowed.size() != 1 || !allowed.get(0)) {
            throw conflict("来源人工复核已变化或不存在，请重新核对并评测");
        }
    }

    private List<KnowledgeDO> baseline(KnowledgePublicationCommand command) {
        try {
            List<KnowledgeDO> rows = json.readValue(command.baselineCorpusJson(), new TypeReference<>() {});
            if (rows == null) throw new IllegalStateException("frozen FAQ baseline is null");
            return rows;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to read frozen FAQ baseline", failure);
        }
    }

    private KnowledgePublicationConflictException conflict(String message) {
        return new KnowledgePublicationConflictException(message);
    }
}
