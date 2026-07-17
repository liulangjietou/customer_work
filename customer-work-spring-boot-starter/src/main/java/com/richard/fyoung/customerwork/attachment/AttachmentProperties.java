package com.richard.fyoung.customerwork.attachment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 附件解析配置（强类型绑定 {@code customer-work.attachment.*}）。
 *
 * <p>独立成一份 {@code @ConfigurationProperties}（不嵌进 {@code CustomerWorkProperties}）：admin 排除了 starter
 * 的自动装配，需要用 {@code @EnableConfigurationProperties(AttachmentProperties.class)} 单独复用这一份配置。
 * 全部给默认值，开箱可用。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@ConfigurationProperties(prefix = "customer-work.attachment")
public class AttachmentProperties {

    /** 是否启用附件解析能力。 */
    private boolean enabled = true;
    /** 存储模式：memory（进程内）| jdbc（数据库持久化）。默认 jdbc——业务数据一律真实库。 */
    private String storeMode = "jdbc";
    /** 落盘根目录（项目无 OSS/MinIO，沿用本地磁盘约定）。 */
    private String baseDir = "./data/attachments";
    /** 单文件大小上限（MB）。 */
    private int maxFileSizeMb = 10;
    /** 解析文本最大字符数，超长截断并在文末追加提示。 */
    private int maxParsedChars = 20000;

    /** 视觉 OCR 配置。 */
    private final Ocr ocr = new Ocr();

    /**
     * 视觉 OCR 配置。
     *
     * <p>两种引擎二选一（{@link #engine}）：<br>
     * - {@code model}（默认）：图片走视觉大模型识别，{@code provider/modelName/apiKey/...} 生效；
     *   {@code api-key} 留空时由装配层回落 {@code customer-work.model.api-key} → 环境变量 {@code DASHSCOPE_API_KEY}，
     *   缺 Key 不影响应用启动（视觉模型惰性构建），仅在真正上传图片时才可能失败并落 FAILED。<br>
     * - {@code paddleocr}：图片走自建 PaddleOCR 开源 serving（{@link #paddle} 生效），无需模型 Key、数据不出内网，
     *   compose 编排见 {@code docker/paddleocr/}。</p>
     */
    @Data
    public static class Ocr {
        /** OCR 引擎：{@code model}（视觉大模型，默认，不改变既有默认行为）| {@code paddleocr}（自建开源 serving）。 */
        private String engine = "model";
        /** 视觉模型厂商（engine=model 生效，默认 dashscope）。 */
        private String provider = "dashscope";
        /** 视觉模型名（engine=model 生效，默认 qwen-vl-max）。 */
        private String modelName = "qwen-vl-max";
        /** 视觉模型 API Key（engine=model 生效，留空则回落 model.api-key / 环境变量）。 */
        private String apiKey;
        /** 自定义端点（engine=model 生效，可空）。 */
        private String baseUrl;
        /** OCR 调用超时（秒，engine=model 生效）。 */
        private long timeoutSeconds = 60;
        /** OCR 提示词（engine=model 生效，中文：逐字提取、保留排版、输出 Markdown、不加解释）。 */
        private String prompt = "请逐字提取这张图片中的全部文字内容，保持原有排版结构，以 Markdown 格式输出，不要添加任何解释说明。";

        /** PaddleOCR 开源 serving 配置（engine=paddleocr 生效）。 */
        private final Paddle paddle = new Paddle();
    }

    /**
     * PaddleOCR 开源 serving 配置（对接官方 PaddleX 3.x OCR pipeline 的 basic serving REST 契约）。
     *
     * <p>契约：{@code POST {base-url}/ocr}，请求体 {@code {"file":"<base64>","fileType":1}}，
     * 响应 {@code result.ocrResults[].prunedResult.rec_texts[]} 为识别文本行。编排见 {@code docker/paddleocr/}。</p>
     */
    @Data
    public static class Paddle {
        /** serving 根地址（默认对齐 compose 暴露的宿主端口 8868）。 */
        private String baseUrl = "http://localhost:8868";
        /** OCR 端点路径（官方 OCR pipeline 固定为 /ocr）。 */
        private String ocrPath = "/ocr";
        /** 调用超时（秒）。 */
        private long timeoutSeconds = 30;
    }
}
