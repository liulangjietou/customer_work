package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customeradmin.workspace.chat.mapper.AiChatAttachmentMapper;
import com.richard.fyoung.customeradmin.workspace.chat.store.AdminChatAttachmentStore;
import com.richard.fyoung.customerwork.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.attachment.AttachmentParser;
import com.richard.fyoung.customerwork.attachment.AttachmentProperties;
import com.richard.fyoung.customerwork.attachment.AttachmentStore;
import com.richard.fyoung.customerwork.attachment.ExcelMarkdownParser;
import com.richard.fyoung.customerwork.attachment.ModelVisionOcrService;
import com.richard.fyoung.customerwork.attachment.TextAttachmentParser;
import com.richard.fyoung.customerwork.attachment.TikaDocumentParser;
import com.richard.fyoung.customerwork.attachment.VisionOcrParser;
import com.richard.fyoung.customerwork.attachment.VisionOcrService;
import com.richard.fyoung.customerwork.config.ChatModelFactory;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Supplier;

/**
 * admin 侧附件域显式装配（仿 {@link AdminAgentRuntimeConfig} 手法）：本模块已 {@code spring.autoconfigure.exclude}
 * 关闭 starter 自动装配，故 starter 的 {@code AttachmentConfig} 不会加载，附件域的 Bean 在此手动 new。
 *
 * <p>与 starter 装配的唯一差异：{@link AttachmentStore} 用 admin 自有的 {@link AdminChatAttachmentStore}
 * （落 admin 库 {@code ai_chat_attachment}，带 agent_code），不引 starter 的 MyBatis 持久层。视觉 OCR 模型
 * <b>惰性构建</b>（缺 api-key 不影响启动，仅真正上传图片时才可能失败落 FAILED），配置源自
 * {@code customer-work.attachment.ocr.*}。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
public class AdminAttachmentConfig {

    private static final String PROVIDER_DASHSCOPE = "dashscope";

    /**
     * 附件存储：admin 自有 {@code ai_chat_attachment} 表实现（覆盖 starter 默认 store）。
     * 返回具体类型 {@link AdminChatAttachmentStore}，既满足 {@link AttachmentParseService} 的
     * {@link AttachmentStore} 依赖，又让 {@code ChatAttachmentService} 能按具体类型注入以调 bind/clearAgentCode。
     */
    @Bean
    public AdminChatAttachmentStore adminChatAttachmentStore(AiChatAttachmentMapper attachmentMapper) {
        return new AdminChatAttachmentStore(attachmentMapper);
    }

    /**
     * 视觉 OCR 服务：视觉模型惰性构建——首次识别才据 {@code ocr.*} 构建模型，缺 api-key 不影响应用启动。
     * dashscope 走工厂的 Key 解析（回落 {@code DASHSCOPE_API_KEY} 环境变量），其它厂商直接用配置值。
     */
    @Bean
    public VisionOcrService visionOcrService(AttachmentProperties properties) {
        AttachmentProperties.Ocr ocr = properties.getOcr();
        Supplier<Model> modelSupplier = () -> {
            String apiKey = PROVIDER_DASHSCOPE.equalsIgnoreCase(ocr.getProvider())
                ? ChatModelFactory.resolveDashScopeKey(ocr.getApiKey())
                : ocr.getApiKey();
            return ChatModelFactory.build(ocr.getProvider(), ocr.getModelName(), apiKey, ocr.getBaseUrl(),
                false, GenerateOptions.builder().build(), null, null);
        };
        return new ModelVisionOcrService(modelSupplier, ocr.getPrompt(), ocr.getTimeoutSeconds());
    }

    /** 附件落盘存储（本地磁盘 base-dir，admin 覆写为 ./data/admin-attachments）。 */
    @Bean
    public AttachmentFileStorage attachmentFileStorage(AttachmentProperties properties) {
        return new AttachmentFileStorage(properties.getBaseDir());
    }

    /**
     * 附件解析编排服务（唯一防御点）。解析器列表：文本 / Excel / Tika 文档 / 图片 OCR，
     * 各自扩展名不相交，顺序不影响正确性。
     */
    @Bean
    public AttachmentParseService attachmentParseService(AttachmentProperties properties,
                                                         AttachmentStore attachmentStore,
                                                         AttachmentFileStorage attachmentFileStorage,
                                                         VisionOcrService visionOcrService) {
        List<AttachmentParser> parsers = List.of(
            new TextAttachmentParser(),
            new ExcelMarkdownParser(),
            new TikaDocumentParser(),
            new VisionOcrParser(visionOcrService));
        return new AttachmentParseService(parsers, attachmentStore, attachmentFileStorage, properties);
    }
}
