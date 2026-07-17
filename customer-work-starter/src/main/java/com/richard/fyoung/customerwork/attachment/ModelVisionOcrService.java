package com.richard.fyoung.customerwork.attachment;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 基于 AgentScope 视觉模型的 OCR 实现：图片 Base64 → {@link ImageBlock} 拼 OCR 提示词 {@link TextBlock}
 * 组成一条 user 消息，调 {@code model.stream(...)} 收集全部返回文本块拼接。
 *
 * <p><b>视觉模型惰性构建（容错要点）：</b>构造只保存 {@link Supplier}，首次 {@link #recognize} 才真正
 * {@code supplier.get()} 构建模型。这样即便 OCR 配置缺 api-key，应用也能正常启动——只有真正上传图片时
 * 才会因缺 Key 抛异常，被编排层捕获落 FAILED，而非启动即崩。模型构建成功后缓存复用。</p>
 * @author owlzhangfq@gmail.com
 */
public class ModelVisionOcrService implements VisionOcrService {

    private static final Logger log = LoggerFactory.getLogger(ModelVisionOcrService.class);

    private final Supplier<Model> modelSupplier;
    private final String prompt;
    private final long timeoutSeconds;

    /** 惰性构建成功后缓存的视觉模型（volatile + synchronized 双检，避免并发重复构建）。 */
    private volatile Model cachedModel;

    public ModelVisionOcrService(Supplier<Model> modelSupplier, String prompt, long timeoutSeconds) {
        this.modelSupplier = modelSupplier;
        this.prompt = prompt;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String recognize(byte[] imageBytes, String mimeType) {
        Model model = resolveModel();
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ImageBlock imageBlock = ImageBlock.builder()
            .source(Base64Source.builder().mediaType(mimeType).data(base64).build())
            .build();
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(imageBlock, TextBlock.builder().text(prompt).build())
            .build();

        List<ChatResponse> responses = model.stream(List.of(userMsg), List.of(), GenerateOptions.builder().build())
            .collectList()
            .block(Duration.ofSeconds(timeoutSeconds));
        if (responses == null || responses.isEmpty()) {
            throw new IllegalStateException("vision model returned empty response");
        }
        String text = responses.stream()
            .flatMap(response -> response.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("vision model returned no text content");
        }
        return text.trim();
    }

    /** 惰性构建 + 缓存视觉模型；构建失败向上抛（由编排层落 FAILED），不缓存失败结果以便下次重试。 */
    private Model resolveModel() {
        Model m = cachedModel;
        if (m == null) {
            synchronized (this) {
                m = cachedModel;
                if (m == null) {
                    m = modelSupplier.get();
                    cachedModel = m;
                    log.info("vision ocr model built lazily, model={}", m.getModelName());
                }
            }
        }
        return m;
    }
}
