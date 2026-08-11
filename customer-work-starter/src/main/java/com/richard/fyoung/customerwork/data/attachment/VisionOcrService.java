package com.richard.fyoung.customerwork.data.attachment;

/**
 * 视觉 OCR 服务 SPI：把图片字节交给视觉大模型识别为文本。
 *
 * <p>默认实现 {@link ModelVisionOcrService}（走 AgentScope 视觉模型）；下游可声明同类型 Bean 覆盖
 * （如接第三方 OCR 服务）。识别失败应抛异常，由编排层 {@link AttachmentParseService} 统一落 FAILED。</p>
 * @author owlzhangfq@gmail.com
 */
public interface VisionOcrService {

    /**
     * 识别图片文本。
     *
     * @param imageBytes 图片字节
     * @param mimeType   图片 MIME（如 {@code image/png}）
     * @return 识别出的文本
     */
    String recognize(byte[] imageBytes, String mimeType);
}
