package com.richard.fyoung.customerwork.attachment;

/**
 * 附件解析结果值对象（不可变）：承载解析出的纯文本与可选元数据（如页数 / sheet 数）。
 *
 * <p>metadata 仅用于日志观测与排障，不进入落库主流程（{@code parsed_text} 只存 {@link #text}）。
 * 常用 {@link #of(String)} 构造无元数据结果。</p>
 *
 * @param text     解析出的文本（非空，可能为长文本，截断由编排层负责）
 * @param metadata 解析附带的说明（可空，如 "pages=3"）
 * @author owlzhangfq@gmail.com
 */
public record ParsedContent(String text, String metadata) {

    /** 无元数据的解析结果。 */
    public static ParsedContent of(String text) {
        return new ParsedContent(text == null ? "" : text, null);
    }

    /** 带元数据的解析结果。 */
    public static ParsedContent of(String text, String metadata) {
        return new ParsedContent(text == null ? "" : text, metadata);
    }
}
