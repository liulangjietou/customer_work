package com.richard.fyoung.customerwork.capability.typesafe;

import java.util.Map;

/**
 * System One 的三种答案，字段语义以官方文档为准。
 *
 * <p><b>两处容易想当然的地方</b>（均已对照 https://docs.typesafe.ai 核实）：</p>
 * <ul>
 *   <li>Score 的 {@code score} 是<b>浮点期望值</b>（各档编号 × 概率求和，如 1.43），会落在两档之间，
 *       不是整数档位。要判「落在哪一档」必须看 {@code probabilities}，见 {@link Score#mostLikelyLevel()}。</li>
 *   <li>Noul <b>没有</b> confidence 字段，单个概率值就是全部信息。</li>
 * </ul>
 */
public sealed interface SystemOneAnswer
    permits SystemOneAnswer.Choice, SystemOneAnswer.Score, SystemOneAnswer.Noul {

    /**
     * @param choice     选中的选项编码
     * @param confidence 置信度 0~1，由概率分布的集中程度计算（衡量一致性，不衡量正确性）
     */
    record Choice(String choice, double confidence) implements SystemOneAnswer {
    }

    /**
     * @param score         档位期望值，范围 0 ~ (档数-1)
     * @param confidence    置信度 0~1
     * @param probabilities 档位编号 → 概率
     */
    record Score(double score, double confidence, Map<Integer, Double> probabilities) implements SystemOneAnswer {

        /** 概率最高的档位；分布为空时返回 -1。 */
        public int mostLikelyLevel() {
            int best = -1;
            double bestProbability = -1;
            for (Map.Entry<Integer, Double> entry : probabilities.entrySet()) {
                if (entry.getValue() > bestProbability) {
                    best = entry.getKey();
                    bestProbability = entry.getValue();
                }
            }
            return best;
        }
    }

    /** @param probability 「是」的概率 0~1 */
    record Noul(double probability) implements SystemOneAnswer {
    }
}
