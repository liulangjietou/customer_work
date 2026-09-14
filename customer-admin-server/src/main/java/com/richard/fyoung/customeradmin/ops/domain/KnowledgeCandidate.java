package com.richard.fyoung.customeradmin.ops.domain;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import java.util.Objects;

/** 候选当前状态与不可变正文修订；修订自身负责判定重复保存和编辑冲突。 */
public record KnowledgeCandidate(String id, String questionHash, long revision, String status,
                                 long sourceReviewRevision, String title, String content, String keyword,
                                 String contentHash, long editedBy, long editedAtMs) {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String PUBLISHED = "PUBLISHED";

    /** 发布意图冻结期间允许后台继续核验该修订，编辑仍只允许 DRAFT。 */
    public void requireCurrent(long expectedRevision) {
        if (revision != expectedRevision || !(DRAFT.equals(status) || PUBLISHING.equals(status))) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "候选版本或状态已变化，请重新核对");
        }
    }

    /** 网络响应丢失后的相同提交只回读已保存修订，不再次推进版本号。 */
    public boolean isSavedSubmission(long expectedRevision, String hash) {
        return revision == expectedRevision + 1 && Objects.equals(contentHash, hash) && DRAFT.equals(status);
    }

    /** 当前修订参与评测或发布时不可编辑；跨聚合的来源核对由 Service 执行。 */
    public void requireEditable(long expectedRevision, String sourceHash) {
        if (revision != expectedRevision || !Objects.equals(questionHash, sourceHash) || !DRAFT.equals(status)) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "候选版本或状态已变化，请保留输入并重新核对");
        }
    }
}
