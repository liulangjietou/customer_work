package com.richard.fyoung.customerwork.data.attachment;

/**
 * 附件解析状态。
 *
 * <p>{@link #SUCCESS} 解析成功、{@code parsedText} 有效；{@link #FAILED} 解析失败（文件已落盘、记录已落库，
 * {@code errorMessage} 说明原因），失败附件不参与上层消息拼接。</p>
 * @author owlzhangfq@gmail.com
 */
public enum AttachmentParseStatus {
    /** 解析成功。 */
    SUCCESS,
    /** 解析失败（可追溯，不拼进消息正文）。 */
    FAILED
}
