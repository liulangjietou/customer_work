package com.richard.fyoung.customerwork.attachment;

/**
 * 附件解析器 SPI（按文件类型分派的解析扩展点）。
 *
 * <p>每类文件对应一个实现：文本直读 / Tika 文档 / Excel 转 Markdown / 图片视觉 OCR。编排层
 * {@link AttachmentParseService} 遍历已注册解析器，取首个 {@link #supports} 命中的执行解析。
 * 解析器内部<b>不做</b>白名单 / 大小等入参校验（fast fail：统一在编排层一处防御），只专注格式解析。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AttachmentParser {

    /**
     * 是否支持该文件（按扩展名为主，mime 为辅）。
     *
     * @param ext  小写扩展名（不含点，如 {@code pdf}）
     * @param mime MIME 类型（可能为空字符串）
     */
    boolean supports(String ext, String mime);

    /**
     * 解析文件字节为纯文本。
     *
     * @param data     文件字节
     * @param fileName 原始文件名（部分解析器据此辅助识别格式）
     * @param ext      小写扩展名（不含点）
     * @param mime     MIME 类型
     * @return 解析结果
     * @throws Exception 解析失败（由编排层统一 catch 落 FAILED 记录）
     */
    ParsedContent parse(byte[] data, String fileName, String ext, String mime) throws Exception;
}
