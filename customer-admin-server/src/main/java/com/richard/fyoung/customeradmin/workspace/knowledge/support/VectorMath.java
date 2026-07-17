package com.richard.fyoung.customeradmin.workspace.knowledge.support;

/**
 * 向量运算工具（P3-2 降级版的应用层相似度计算）。纯函数，可离线确定性单测。
 * @author owlzhangfq@gmail.com
 */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * 余弦相似度：{@code cos = (a·b) / (|a|*|b|)}，范围 [-1, 1]。
     * 长度不一致或任一为零向量时返回 0（无意义相似度按不相关处理，fast fail 交上层排序自然过滤）。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0d;
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0d || normB == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
