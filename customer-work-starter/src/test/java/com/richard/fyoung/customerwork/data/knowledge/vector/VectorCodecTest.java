package com.richard.fyoung.customerwork.data.knowledge.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 向量定长编解码测试。
 *
 * @author owlzhangfq@gmail.com
 */
class VectorCodecTest {

    @Test
    @DisplayName("编解码往返必须逐位一致")
    void roundTripIsExact() {
        float[] original = {0.1f, -0.25f, 3.14159f, 0f, -1f, Float.MIN_VALUE, Float.MAX_VALUE};

        float[] decoded = VectorCodec.decode(VectorCodec.encode(original));

        assertArrayEquals(original, decoded, 0f, "float32 编解码不得有精度损失");
    }

    @Test
    @DisplayName("编码长度是维度的 4 倍")
    void encodedLengthMatchesDimensions() {
        assertEquals(4096, VectorCodec.encode(new float[1024]).length, "1024 维应占 4096 字节");
        assertEquals(4096, VectorCodec.byteLength(1024));
    }

    @Test
    @DisplayName("空与 null 一律编成空数组")
    void nullAndEmptyEncodeToEmpty() {
        assertEquals(0, VectorCodec.encode(null).length);
        assertEquals(0, VectorCodec.encode(new float[0]).length);
        assertEquals(0, VectorCodec.decode(null).length);
        assertEquals(0, VectorCodec.decode(new byte[0]).length);
    }

    /**
     * 长度不是 4 的整数倍说明这段字节不是本编码写出来的。
     *
     * <p>必须显式失败而不是静默截断：截断出来的短向量照样能算余弦、照样有分数，
     * 只是分数毫无意义——那种错误没有任何迹象可循。</p>
     */
    @Test
    @DisplayName("非法字节长度必须显式失败，不得静默截断")
    void malformedLengthFailsFast() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> VectorCodec.decode(new byte[]{1, 2, 3}));

        assertEquals(true, error.getMessage().contains("整数倍"));
    }

    /**
     * 字节序是落库契约，不能改。
     *
     * <p>改了会让存量向量整体读成噪声，而且不报错，只表现为检索结果突然毫不相关。
     * 这里把 1.0f 的大端表示写死，任何字节序变更都会在这里立刻暴露。</p>
     */
    @Test
    @DisplayName("字节序固定为大端，是不可变更的落库契约")
    void byteOrderIsBigEndianContract() {
        byte[] encoded = VectorCodec.encode(new float[]{1.0f});

        assertArrayEquals(new byte[]{0x3F, (byte) 0x80, 0x00, 0x00}, encoded,
            "1.0f 的大端表示应为 3F 80 00 00；这里变红说明字节序被改了，存量向量会整体失效");
    }
}
