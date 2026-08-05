package com.richard.fyoung.customerwork.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link VectorMath} 单测：余弦相似度的正常值与退化边界（长度不一致/零向量）。
 * @author owlzhangfq@gmail.com
 */
class VectorMathTest {

    @Test
    void identicalVectorsCosineIsOne() {
        assertEquals(1.0d, VectorMath.cosine(new float[]{1, 2, 3}, new float[]{1, 2, 3}), 1e-9);
    }

    @Test
    void orthogonalVectorsCosineIsZero() {
        assertEquals(0.0d, VectorMath.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
    }

    @Test
    void oppositeVectorsCosineIsMinusOne() {
        assertEquals(-1.0d, VectorMath.cosine(new float[]{1, 1}, new float[]{-1, -1}), 1e-9);
    }

    @Test
    void proportionalVectorsCosineIsOne() {
        assertEquals(1.0d, VectorMath.cosine(new float[]{2, 4}, new float[]{1, 2}), 1e-9);
    }

    @Test
    void lengthMismatchReturnsZero() {
        assertEquals(0.0d, VectorMath.cosine(new float[]{1, 2, 3}, new float[]{1, 2}), 1e-9);
    }

    @Test
    void zeroVectorReturnsZero() {
        assertEquals(0.0d, VectorMath.cosine(new float[]{0, 0}, new float[]{1, 1}), 1e-9);
    }

    @Test
    void nullInputReturnsZero() {
        assertEquals(0.0d, VectorMath.cosine(null, new float[]{1, 1}), 1e-9);
    }
}
