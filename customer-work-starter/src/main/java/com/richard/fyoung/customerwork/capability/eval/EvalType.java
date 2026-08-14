package com.richard.fyoung.customerwork.capability.eval;

/**
 * 评测类型。
 *
 * <p>两类评测的运行代价与依赖完全不同，故分开记录、分开对比：意图评测纯离线确定性
 * （只跑规则快车道，无模型调用，可进 CI 门禁）；质量评测要真实模型 Key 且逐条调 Judge，
 * 跑一次有实打实的 token 成本，只适合按需触发。</p>
 *
 * <p>把两者塞进同一条趋势线是错的——它们的主指标口径不同（准确率 vs 平均分），
 * 混在一起看会得出"改了提示词准确率掉了"这种其实是换了评测类型的假结论。</p>
 * @author owlzhangfq@gmail.com
 */
public enum EvalType {

    /** 意图路由评测：离线确定性，量化规则快车道的准确率与覆盖率。 */
    INTENT,

    /** 回复质量评测：LLM-as-Judge 打分（1-5 分），需真实模型 Key。 */
    QUALITY
}
