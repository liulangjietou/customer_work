package com.richard.fyoung.customerwork.safety.sensitiveword;

/**
 * 一次命中结果（自动机在归一化文本上的一次匹配）。
 *
 * @param word  命中的敏感词（含 category / action）
 * @param start 命中片段在<b>归一化文本</b>中的起始下标（含）
 * @param end   命中片段在<b>归一化文本</b>中的结束下标（不含）
 * @author owlzhangfq@gmail.com
 */
public record SensitiveWordHit(SensitiveWord word, int start, int end) {
}
