package com.richard.fyoung.customeradmin.aiconfig.channelrobot;

/** 微信公众号回调模式。 */
public enum WeChatCallbackMode {

    /** 兼容模式：签名校验后直接解析明文 XML。 */
    PLAINTEXT("plaintext"),
    /** 安全模式：校验 msg_signature 并使用 EncodingAESKey 解密。 */
    SAFE("safe");

    private final String code;

    WeChatCallbackMode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static WeChatCallbackMode fromCode(String code) {
        for (WeChatCallbackMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return null;
    }
}
