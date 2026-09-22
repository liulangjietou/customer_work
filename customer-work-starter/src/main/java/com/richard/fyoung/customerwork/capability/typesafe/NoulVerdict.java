package com.richard.fyoung.customerwork.capability.typesafe;

/**
 * 一个是/否判定的结果。
 *
 * @param probability 「是」的概率 0~1
 * @param model       实际服务的模型版本
 * @param latencyMs   调用耗时
 */
public record NoulVerdict(double probability, String model, long latencyMs) {

    /** 概率是否达到门槛。 */
    public boolean atLeast(double threshold) {
        return probability >= threshold;
    }
}
