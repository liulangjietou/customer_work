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
    REVIEW,

    /** VibeCoding Plan Mode 计划确认/拒绝（HITL，需求 P1-1）。 */
    PLAN_CONFIRM,

    /** 多 Agent 协作编程流水（P3-1，降级版：MultiAgentOrchestrator 顺序编排多角色）。 */
    COLLAB_STREAM,

    /** 代码知识库索引构建（P3-2，扫描源码→分块→Embedding→入库）。 */
    KNOWLEDGE_INDEX,

    /** 代码知识库检索增强问答（P3-2，语义检索 top-k → 一次性模型作答）。 */
    KNOWLEDGE_ASK,

    /** 用户在会话沙箱内交互式执行命令（P1-2）。 */
    COMMAND_EXECUTE,

    /** 根据异常堆栈或日志诊断并修复问题（P2-1）。 */
    DIAGNOSE,

    /** 经过显式计划确认的自动化重构（P2-2）。 */
    REFACTOR,

    /** 手动停止并清理会话沙箱（P2-3）。 */
    SANDBOX_CLEANUP
}
