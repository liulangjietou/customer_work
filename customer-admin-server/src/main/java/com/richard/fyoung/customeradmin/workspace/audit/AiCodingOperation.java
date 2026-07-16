package com.richard.fyoung.customeradmin.workspace.audit;

/**
 * AI 编码操作类型（审计日志 {@code operation} 列取值，需求文档 §5.2/§5.3）。
 *
 * <p>只收录"AI 编码助手"域的操作：对话式编码、用户手工保存文件、Git 助手三件套。
 * 后续新功能（回滚/Review 等）落地时在此追加，禁止在埋点处写裸字符串。</p>
 * @author owlzhangfq@gmail.com
 */
public enum AiCodingOperation {

    /** VibeCoding 流式对话（Agent 读写 workspace，含模型多轮调用）。 */
    CHAT_STREAM,

    /** 用户在工作区文件查看器中手工保存文件。 */
    FILE_SAVE,

    /** Git 助手 · diff 摘要（只读，模型一次性调用）。 */
    GIT_DIFF_SUMMARY,

    /** Git 助手 · 生成 commit message（只读，模型一次性调用）。 */
    COMMIT_MESSAGE,

    /** Git 助手 · 生成 PR description（只读，模型一次性调用）。 */
    PR_DESCRIPTION,

    /** VibeCoding 会话一键回滚（破坏性：checkout + clean 恢复到 baseline）。 */
    ROLLBACK,

    /** Git 助手 · AI 代码审查（只读，对本轮 diff 一次性调用模型输出结构化审查意见）。 */
    REVIEW
}
