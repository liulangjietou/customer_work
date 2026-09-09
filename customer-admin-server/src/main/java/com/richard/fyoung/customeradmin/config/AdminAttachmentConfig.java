package com.richard.fyoung.customeradmin.config;

import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import com.richard.fyoung.customerwork.data.attachment.VisionOcrUsageRecorder;
import com.richard.fyoung.customerwork.data.attachment.MeteredVisionOcrUsageRecorder;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.AiChatAttachmentMapper;
import com.richard.fyoung.customeradmin.workspace.chat.store.AdminChatAttachmentStore;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorages;
import com.richard.fyoung.customerwork.data.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.data.attachment.AttachmentParser;
import com.richard.fyoung.customerwork.data.attachment.DocumentTextExtractor;
import com.richard.fyoung.customerwork.data.attachment.AttachmentProperties;
import com.richard.fyoung.customerwork.data.attachment.AttachmentStore;
import com.richard.fyoung.customerwork.data.attachment.ExcelMarkdownParser;
import com.richard.fyoung.customerwork.data.attachment.TextAttachmentParser;
import com.richard.fyoung.customerwork.data.attachment.TikaDocumentParser;
import com.richard.fyoung.customerwork.data.attachment.VisionOcrParser;
import com.richard.fyoung.customerwork.data.attachment.VisionOcrService;
import com.richard.fyoung.customerwork.data.attachment.VisionOcrServices;
import com.richard.fyoung.customerwork.infra.config.ChatModelFactory;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
     * 视觉 OCR 服务：按 {@code ocr.engine} 选引擎（model 视觉大模型 / paddleocr 自建 serving），选型逻辑收敛在
     * starter 的 {@link VisionOcrServices}（与 8080 侧同一份，避免漂移）。engine=model 时视觉模型惰性构建——
     * 首次识别才据 {@code ocr.*} 构建，缺 api-key 不影响启动；dashscope 走工厂的 Key 解析（回落
     * {@code DASHSCOPE_API_KEY} 环境变量），其它厂商直接用配置值。
     */
    @Bean
    public VisionOcrService visionOcrService(AttachmentProperties properties,
                                             ObjectProvider<MeterRegistry> meterRegistryProvider,
                                             ObjectProvider<SubjectQuotaGuard> quotaGuardProvider) {
        AttachmentProperties.Ocr ocr = properties.getOcr();
        Supplier<Model> modelSupplier = () -> {
            String apiKey = ModelProviders.DASHSCOPE.equalsIgnoreCase(ocr.getProvider())
                ? ChatModelFactory.resolveDashScopeKey(ocr.getApiKey())
                : ocr.getApiKey();
            return ChatModelFactory.build(ocr.getProvider(), ocr.getModelName(), apiKey, ocr.getBaseUrl(),
                false, GenerateOptions.builder().build(), null, null);
        };
        // 与 8080 侧同一套记账口径，避免两边对同一次 OCR 算出不同的账
        VisionOcrUsageRecorder usageRecorder = new MeteredVisionOcrUsageRecorder(
            meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable(),
            quotaGuardProvider == null ? null : quotaGuardProvider.getIfAvailable());
        return VisionOcrServices.create(properties, modelSupplier, usageRecorder,
            meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    /** 附件文件存储：按 {@code storage.type} 选后端（local 本地磁盘 / minio 对象存储），选型收敛在 starter 的 {@link AttachmentFileStorages}（与 8080 侧同一份）。 */
    @Bean
    public AttachmentFileStorage attachmentFileStorage(AttachmentProperties properties) {
        return AttachmentFileStorages.create(properties);
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
        List<AttachmentParser> parsers = new ArrayList<>(documentParsers(properties));
        // 图片 OCR 只属于对话附件：那里用户传的就是截图，一次视觉模型调用是预期内的
        parsers.add(new VisionOcrParser(visionOcrService));
        return new AttachmentParseService(List.copyOf(parsers), attachmentStore,
            attachmentFileStorage, properties);
    }

    /**
     * 知识库文档入库用的文本提取器。
     *
     * <p>starter 的自动装配在 admin 被整体 exclude，需要它的能力就得在这里显式 new
     * （项目既定约定）。刻意<b>不</b>把 {@code VisionOcrParser} 交给它：
     * {@code DocumentTextExtractor} 的类型白名单本就不含图片，传进去是死代码，
     * 还会让知识库入库凭空依赖视觉模型的可用性与配置。</p>
     */
    @Bean
    public DocumentTextExtractor documentTextExtractor(AttachmentProperties properties) {
        return new DocumentTextExtractor(documentParsers(properties));
    }

    /** 纯文档解析器（不含图片 OCR），两处装配共用同一份定义。 */
    private List<AttachmentParser> documentParsers(AttachmentProperties properties) {
        return List.of(
            new TextAttachmentParser(properties.getExtraTextExtensions()),
            new ExcelMarkdownParser(),
            new TikaDocumentParser());
    }
}
