package com.richard.fyoung.customeradmin.common.crypto;

import com.richard.fyoung.customerwork.common.crypto.AesGcmCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES/GCM 加解密工具，实现委托给 starter 的 {@link AesGcmCrypto}（两侧唯一实现，避免算法双份维护）。
 *
 * <p>本类只负责 admin 侧的装配契约：作为 Spring Bean 暴露，密钥由 {@code admin.aes-secret-key}
 * 环境变量注入（不入库不硬编码）。使用场景为敏感字段加密存储——{@code ai_model_config.api_key}、
 * {@code sql_datasource.password}、知识库/渠道机器人凭据等。</p>
 *
 * <p><b>密文格式不可变更</b>：Base64(IV[12B] + 密文)，库中已有大量存量密文按此格式加密。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class AesGcmCryptoUtil {

    /** 密钥配置项名，失败信息里回显，便于运维定位。 */
    private static final String KEY_PROPERTY = "admin.aes-secret-key";

    private final AesGcmCrypto crypto;

    public AesGcmCryptoUtil(@Value("${admin.aes-secret-key}") String secretKey) {
        this.crypto = new AesGcmCrypto(secretKey, KEY_PROPERTY);
    }

    /** 加密明文，返回 Base64（IV + 密文）。 */
    public String encrypt(String plainText) {
        return crypto.encrypt(plainText);
    }

    /** 解密 {@link #encrypt} 产出的 Base64 密文。 */
    public String decrypt(String cipherTextBase64) {
        return crypto.decrypt(cipherTextBase64);
    }

    /** 掩码显示：只保留末 4 位，其余替换为 *（用于列表/详情接口回显，不暴露明文）。 */
    public static String mask(String plainText) {
        return AesGcmCrypto.mask(plainText);
    }
}
