package com.richard.fyoung.customerwork.capability.eval;

/**
 * 落库的评测用例——让评测集能随 badcase 增长，而不是被钉死在 classpath 里。
 *
 * <p>原来两类用例都只存在于 jar 内的 JSON 文件里，运行时只读。这意味着"把 badcase 转成评测用例"
 * 这件事根本无处落地：数据飞轮的最后一环缺的正是这张表。</p>
 *
 * <p>与 {@link EvalCase}/{@link QualityEvalCase} 的关系：那两个是各自 Runner 的<b>入参形状</b>，
 * 字段随评测类型而异；本 record 是统一的<b>存储形状</b>，靠 {@link #evalType} 区分，
 * 用 {@link #toIntentCase()}/{@link #toQualityCase()} 转回去。两类用例分表存会让"回流一条用例"
 * 这个动作按类型分叉，而它本该只是一次插入。</p>
 *
 * @param caseId      用例编号（同类型内唯一；与种子用例同 ID 即覆盖种子）
 * @param evalType    所属评测类型
 * @param input       用户输入
 * @param expected    期望值：INTENT 存期望意图（{@code null} 表示期望快车道不命中）；QUALITY 存期望要点
 * @param category    归类标签
 * @param source      来源
 * @param enabled     是否参与评测；置 false 可屏蔽掉一条种子用例而无需改代码
 * @param originRef   溯源引用：来自 badcase 时记 badcase ID，便于回看原始会话
 * @param createdAtMs 创建时间戳（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record PersistedEvalCase(
    String caseId,
    EvalType evalType,
    String input,
    String expected,
    String category,
    EvalCaseSource source,
    boolean enabled,
    String originRef,
    long createdAtMs
) {

    /** 转成意图评测入参。 */
    public EvalCase toIntentCase() {
        return new EvalCase(caseId, input, expected, category);
    }

    /** 转成质量评测入参。 */
    public QualityEvalCase toQualityCase() {
        return new QualityEvalCase(caseId, input, expected, category);
    }
}
