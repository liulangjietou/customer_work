package com.richard.fyoung.customerwork.capability.eval;

/**
 * 评测用例的来源。
 *
 * <p>来源要留痕，否则评测集长大之后没人说得清某条用例当初为什么加进来——
 * 尤其是从 badcase 回流的那些，它们对应的是真实翻过车的场景，删之前理应先看一眼原始会话。</p>
 * @author owlzhangfq@gmail.com
 */
public enum EvalCaseSource {

    /** 随代码走的种子用例（classpath 下的 JSON），改动经过 code review。 */
    SEED,

    /** 由 badcase 筛选后回流：真实翻过车的场景，是评测集最有价值的增量。 */
    BADCASE,

    /** 运营在后台手工添加。 */
    MANUAL,

    /** 通过数据集导入接口批量写入。 */
    IMPORT
}
