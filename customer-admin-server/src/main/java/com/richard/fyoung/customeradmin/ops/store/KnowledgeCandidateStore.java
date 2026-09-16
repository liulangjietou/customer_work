package com.richard.fyoung.customeradmin.ops.store;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidate;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateSaveRequest;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 两张 Admin 表同事务提交；所有查询显式使用二进制租户列，不依赖可关闭的租户插件。 */
@Repository
public class KnowledgeCandidateStore {
    private static final String CURRENT = "SELECT c.id,c.question_hash,c.revision,c.status,r.source_review_revision,"
        + "r.title,r.content,r.keyword,r.content_hash,r.edited_by,r.edited_at_ms FROM ai_knowledge_candidate c "
        + "JOIN ai_knowledge_candidate_revision r ON r.tenant_id=c.tenant_id AND r.candidate_id=c.id "
        + "AND r.revision=c.revision WHERE c.tenant_id=? AND ";
    private final JdbcTemplate jdbc;

    public KnowledgeCandidateStore(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    /** 候选不存在与当前租户无权访问时均返回空。 */
    public Optional<KnowledgeCandidate> find(String tenant, String id) {
        return jdbc.query(CURRENT + "c.id=?", this::read, tenant, id).stream().findFirst();
    }

    /** 一个来源只保留一条候选主记录，重复打开编辑器返回当前已保存内容。 */
    public Optional<KnowledgeCandidate> bySource(String tenant, String questionHash) {
        return jdbc.query(CURRENT + "c.question_hash=?", this::read, tenant, questionHash).stream().findFirst();
    }

    /** 与父改进记录的发布意图同事务冻结修订，编辑保存与入队只有一个能先提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reservePublication(String tenant, String id, long revision) {
        if (jdbc.update("UPDATE ai_knowledge_candidate SET status=? WHERE tenant_id=? AND id=? AND revision=? AND status=?",
            KnowledgeCandidate.PUBLISHING, tenant, id, revision, KnowledgeCandidate.DRAFT) != 1) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "候选版本或状态已变化，请重新核对发布内容");
        }
    }

    /** 已知失败才解冻；未知结果保留 PUBLISHING，等待同任务回执核对。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finishPublication(String tenant, String id, long revision, boolean published) {
        if (jdbc.update("UPDATE ai_knowledge_candidate SET status=? WHERE tenant_id=? AND id=? AND revision=? AND status=?",
            published ? KnowledgeCandidate.PUBLISHED : KnowledgeCandidate.DRAFT,
            tenant, id, revision, KnowledgeCandidate.PUBLISHING) != 1) {
            throw new IllegalStateException("knowledge candidate publication reservation is missing");
        }
    }

    /** 悲观行锁与修订比较共同防止并发覆盖，正文修订只插入不更新。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeCandidate save(String tenant, String id, KnowledgeCandidateSaveRequest request, long actor, long now) {
        KnowledgeCandidate current = jdbc.query(CURRENT + "c.id=? FOR UPDATE", this::read, tenant, id)
            .stream().findFirst().orElse(null);
        String title = request.title().trim();
        String content = request.content().trim();
        String keyword = request.keyword().trim();
        String hash = EvalFingerprint.of("knowledge-candidate-v1", id, request.questionHash(),
            request.sourceReviewRevision(), title, content, keyword);
        if (current != null && current.isSavedSubmission(request.expectedRevision(), hash)) return current;
        long next;
        if (current == null) {
            if (request.expectedRevision() != 0) throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT);
            next = 1;
            jdbc.update("INSERT INTO ai_knowledge_candidate(tenant_id,id,question_hash,revision,status) VALUES(?,?,?,?,?)",
                tenant, id, request.questionHash(), next, KnowledgeCandidate.DRAFT);
        } else {
            current.requireEditable(request.expectedRevision(), request.questionHash());
            next = current.revision() + 1;
            jdbc.update("UPDATE ai_knowledge_candidate SET revision=? WHERE tenant_id=? AND id=? AND revision=?",
                next, tenant, id, current.revision());
        }
        jdbc.update("INSERT INTO ai_knowledge_candidate_revision(tenant_id,candidate_id,revision,source_review_revision,"
                + "title,content,keyword,content_hash,edited_by,edited_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",
            tenant, id, next, request.sourceReviewRevision(), title, content, keyword, hash, actor, now);
        return new KnowledgeCandidate(id, request.questionHash(), next, KnowledgeCandidate.DRAFT,
            request.sourceReviewRevision(), title, content, keyword, hash, actor, now);
    }

    private KnowledgeCandidate read(ResultSet row, int index) throws SQLException {
        return new KnowledgeCandidate(row.getString("id"), row.getString("question_hash"), row.getLong("revision"),
            row.getString("status"), row.getLong("source_review_revision"), row.getString("title"),
            row.getString("content"), row.getString("keyword"), row.getString("content_hash"),
            row.getLong("edited_by"), row.getLong("edited_at_ms"));
    }
}
