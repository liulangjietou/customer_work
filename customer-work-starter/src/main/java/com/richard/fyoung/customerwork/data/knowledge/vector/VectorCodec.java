package com.richard.fyoung.customerwork.data.knowledge.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量的定长二进制编解码。
 *
 * <p><b>为什么不继续用 JSON</b>：知识分片的向量此前以 JSON 文本存在 {@code LONGTEXT} 列里，
 * 检索时每一条都要走一次 {@code ObjectMapper.readValue(..., float[].class)}。
 * 按 1024 维、一个中等规模知识库 1 万 chunk 估算，单次提问要解析约 40MB 的浮点文本，
 * 而且这发生在请求线程上。定长 float32 编码把同一份数据压到约 4MB，且读取只是一次
 * {@code ByteBuffer} 顺序扫描，没有解析。</p>
 *
 * <p><b>字节序固定为大端</b>：{@code ByteBuffer} 默认就是大端，跨平台一致，
 * 且与 {@code DataOutputStream} 等 JDK 内建写法互通。这个选择一旦落库就不能改——
 * 改了会让存量向量整体读成噪声，而且不会报错，只表现为检索结果突然变得毫不相关。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class VectorCodec {

    /** 单个 float32 占用的字节数。 */
    public static final int BYTES_PER_FLOAT = Float.BYTES;

    private VectorCodec() {
    }

    /** 编码为定长字节序列；{@code null} 或空向量返回长度为 0 的数组。 */
    public static byte[] encode(float[] vector) {
        if (vector == null || vector.length == 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * BYTES_PER_FLOAT)
            .order(ByteOrder.BIG_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /**
     * 解码定长字节序列。
     *
     * <p>长度不是 4 的整数倍说明这段字节根本不是本编码写出来的——直接失败，
     * 不要静默截断成一个短向量：短向量照样能算出余弦分数，只是分数毫无意义，
     * 而那种错误没有任何迹象可循。</p>
     */
    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        if (bytes.length % BYTES_PER_FLOAT != 0) {
            throw new IllegalArgumentException(
                "向量字节长度必须是 " + BYTES_PER_FLOAT + " 的整数倍，实际 " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] vector = new float[bytes.length / BYTES_PER_FLOAT];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /** 该维度的向量编码后占用的字节数。 */
    public static int byteLength(int dimensions) {
        return dimensions * BYTES_PER_FLOAT;
    }
}
