package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.capability.knowledgegap.entity.KnowledgeGapReviewDO;
import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapReviewMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 复核与审计在客服端同库提交；不创建改进状态机，不触碰知识和发布数据。 */
public class MybatisKnowledgeGapReviewStore {
    private final KnowledgeGapMapper gapMapper;
    private final KnowledgeGapReviewMapper reviewMapper;
    private final TransactionTemplate transaction;

    public MybatisKnowledgeGapReviewStore(KnowledgeGapMapper gapMapper,
                                          KnowledgeGapReviewMapper reviewMapper, DataSource dataSource) {
        this.gapMapper = gapMapper;
        this.reviewMapper = reviewMapper;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 查询授权分区内的原始问题，缺失时返回空。 */
    public Optional<KnowledgeGap> find(String scopeId, String questionHash) {
        TenantContext.require();
        return Optional.ofNullable(gapMapper.selectByHash(scopeId, questionHash))
            .map(MybatisKnowledgeGapStore::toDomain);
    }

    /** 历史只读且分页，不因当前分类被改动而覆盖此前理由。 */
    public List<KnowledgeGapReview> history(String scopeId, String questionHash, long beforeRevision) {
        TenantContext.require();
        return reviewMapper.history(scopeId, questionHash, beforeRevision).stream().map(row ->
            new KnowledgeGapReview(row.getRevision(), KnowledgeGapCategory.valueOf(row.getPreviousCategory()),
                KnowledgeGapPriority.valueOf(row.getPreviousPriority()), KnowledgeGapCategory.valueOf(row.getCategory()),
                KnowledgeGapPriority.valueOf(row.getPriority()), row.getReason(), row.getReviewedBy(),
                row.getReviewedAtMs(), row.getSignalCount())).toList();
    }

    /** 比较分类修订后保存；任一 SQL 失败都回滚分类与历史，不吞掉写入异常。 */
    public KnowledgeGap review(String scopeId, String questionHash, long expectedRevision,
                                KnowledgeGapCategory category, KnowledgeGapPriority priority,
                                String reason, String operator) {
        String tenantId = TenantContext.require();
        return transaction.execute(status -> {
            var row = gapMapper.selectByHash(scopeId, questionHash);
            if (row == null) throw new NoSuchElementException("Knowledge gap not found");
            if (row.getReviewRevision() != expectedRevision) throw new KnowledgeGapReviewConflictException();
            var audit = new KnowledgeGapReviewDO();
            audit.setTenantId(tenantId);
            audit.setScopeId(scopeId);
            audit.setQuestionHash(questionHash);
            audit.setRevision(expectedRevision + 1);
            audit.setPreviousCategory(row.getCategory());
            audit.setPreviousPriority(row.getPriority());
            audit.setCategory(category.name());
            audit.setPriority(priority.name());
            audit.setReason(reason);
            audit.setReviewedBy(operator);
            audit.setReviewedAtMs(System.currentTimeMillis());
            audit.setSignalCount(row.getMissCount());
            row.setCategory(category.name());
            row.setPriority(priority.name());
            row.setClassificationOrigin(KnowledgeGapClassification.Origin.MANUAL.name());
            row.setClassificationReason(reason);
            row.setReviewedBy(operator);
            row.setReviewedAtMs(audit.getReviewedAtMs());
            if (gapMapper.updateClassification(row, expectedRevision) != 1) {
                throw new KnowledgeGapReviewConflictException();
            }
            reviewMapper.insert(audit);
            row.setReviewRevision(expectedRevision + 1);
            return MybatisKnowledgeGapStore.toDomain(row);
        });
    }
}
