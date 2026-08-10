package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.common.crypto.AesGcmCrypto;

/**
 * AES/GCM 解密器（Nacos 运行时配置消费端），实现委托给 {@link AesGcmCrypto}。
 *
 * <p>密文由 admin 侧用同一算法、同一密钥加密后发布到 Nacos，starter 拉到后用本类解密还原明文注入模型链。
 * 保留本类而不让调用方直接用 {@link AesGcmCrypto}，是为了把「密钥来自
 * {@code customer-work.nacos.config-aes-key}」这条配置契约固化在消费端——两侧密钥配置项不同名是刻意的解耦。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class AesGcmDecryptor {

    /** 密钥配置项名，失败信息里回显，便于运维定位。 */
    private static final String KEY_PROPERTY = "customer-work.nacos.config-aes-key";

    private final AesGcmCrypto crypto;

    public AesGcmDecryptor(String secretKey) {
        this.crypto = new AesGcmCrypto(secretKey, KEY_PROPERTY);
    }

    /**
     * 解密 admin 侧加密产出的 Base64 密文。
     *
     * @param cipherTextBase64 Base64(IV + cipherText)
     * @return 明文
     * @throws IllegalStateException 密文非法 / 密钥不匹配 / tag 校验失败
     */
    public String decrypt(String cipherTextBase64) {
        return crypto.decrypt(cipherTextBase64);
    }
}
