package com.richard.fyoung.customerwork.data.attachment;

import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import com.richard.fyoung.customerwork.core.model.ModelResponses;
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

    /** 用量记账；默认不记，由装配侧注入真实实现。 */
    private final VisionOcrUsageRecorder usageRecorder;

    /** 兼容既有显式构造（离线单测）：不记账。 */
    public ModelVisionOcrService(Supplier<Model> modelSupplier, String prompt, long timeoutSeconds) {
        this(modelSupplier, prompt, timeoutSeconds, VisionOcrUsageRecorder.noop());
    }

    public ModelVisionOcrService(Supplier<Model> modelSupplier, String prompt, long timeoutSeconds,
                                 VisionOcrUsageRecorder usageRecorder) {
        this.modelSupplier = modelSupplier;
        this.prompt = prompt;
        this.timeoutSeconds = timeoutSeconds;
        this.usageRecorder = usageRecorder == null ? VisionOcrUsageRecorder.noop() : usageRecorder;
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
        // 这是一次真实的模型调用，必须记账——它此前完全绕开了 AgentCallTimingMiddleware
        usageRecorder.record(model.getModelName(), lastUsage(responses));
        String text = ModelResponses.text(responses);
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("vision model returned no text content");
        }
        return text.trim();
    }

    /**
     * 取本次调用的最终用量。
     *
     * <p><b>取最后一个非空而不是逐片累加</b>：{@code ChatUsage} 的语义是"本次调用的累计用量"，
     * 逐片相加会把同一批 token 重复计数。而 OCR 建模时 {@code stream=false}，
     * 响应通常只有一片，这里的"最后一个"多数时候就是唯一一个。</p>
     */
    private ChatUsageSnapshot lastUsage(List<ChatResponse> responses) {
        for (int i = responses.size() - 1; i >= 0; i--) {
            ChatResponse response = responses.get(i);
            if (response != null && response.getUsage() != null) {
                return ChatUsageSnapshot.from(response.getUsage());
            }
        }
        return ChatUsageSnapshot.empty();
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
