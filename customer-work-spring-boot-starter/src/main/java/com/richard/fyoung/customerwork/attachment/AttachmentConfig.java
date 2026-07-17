package com.richard.fyoung.customerwork.attachment;

import com.richard.fyoung.customerwork.attachment.mapper.ChatAttachmentMapper;
import com.richard.fyoung.customerwork.config.ChatModelFactory;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Supplier;

/**
 * 附件域装配：按 {@code customer-work.attachment.store-mode} 选择存储实现，装配解析器、视觉 OCR 与编排服务。
 *
 * <p>所有 Bean 均 {@code @ConditionalOnMissingBean}，下游（admin 排除了 starter 自动装配）可声明同类型 Bean
 * 整体覆盖；{@link AttachmentStore} 与 {@link VisionOcrService} 尤其允许下游注入自有实现（如 admin 用自己的
 * {@code ai_chat_attachment} 表 / 第三方 OCR）。{@code enabled=false} 时整体不装配。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
@ConditionalOnProperty(prefix = "customer-work.attachment", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AttachmentConfig {

    private static final Logger log = LoggerFactory.getLogger(AttachmentConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    /**
     * 附件存储：默认 jdbc（业务数据一律真实库）。jdbc 需持久化环境（{@code CustomerWorkPersistenceConfig}）已激活
     * 提供 Mapper；若声明 jdbc 但持久化环境未激活（无其它域触发），惰性取不到 Mapper 则降级内存并告警，避免启动失败。
     */
    @Bean
    @ConditionalOnMissingBean(AttachmentStore.class)
    public AttachmentStore attachmentStore(AttachmentProperties properties,
                                           ObjectProvider<ChatAttachmentMapper> mapperProvider) {
        if (STORE_MODE_JDBC.equalsIgnoreCase(properties.getStoreMode())) {
            ChatAttachmentMapper mapper = mapperProvider.getIfAvailable();
            if (mapper != null) {
                log.info("attachment store: jdbc (MyBatis-Plus 实现, table=cw_chat_attachment)");
                return new MybatisAttachmentStore(mapper);
            }
            log.info("attachment store: jdbc requested but persistence env inactive, "
                + "fallback to memory (set another domain store-mode=jdbc to activate persistence)");
            return new InMemoryAttachmentStore();
        }
        log.info("attachment store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryAttachmentStore();
    }

    /**
     * 视觉 OCR 服务：按 {@code ocr.engine} 选引擎（model 视觉大模型 / paddleocr 自建 serving），选型逻辑收敛在
     * {@link VisionOcrServices}。engine=model 时视觉模型惰性构建——首次识别才据 {@code ocr.*} 构建，缺 api-key
     * 不影响应用启动；api-key 优先取 {@code ocr.api-key}，为空回落 {@code customer-work.model.api-key}（再由工厂回落环境变量）。
     */
    @Bean
    @ConditionalOnMissingBean(VisionOcrService.class)
    public VisionOcrService visionOcrService(AttachmentProperties properties,
                                             CustomerWorkProperties customerWorkProperties) {
        AttachmentProperties.Ocr ocr = properties.getOcr();
        String modelApiKey = customerWorkProperties.getModel().getApiKey();
        Supplier<Model> modelSupplier = () -> {
            String configured = StringUtils.hasText(ocr.getApiKey()) ? ocr.getApiKey() : modelApiKey;
            // dashscope 走工厂的 Key 解析（回落 DASHSCOPE_API_KEY 环境变量）；其它厂商直接用配置值
            String apiKey = "dashscope".equalsIgnoreCase(ocr.getProvider())
                ? ChatModelFactory.resolveDashScopeKey(configured)
                : configured;
            return ChatModelFactory.build(ocr.getProvider(), ocr.getModelName(), apiKey, ocr.getBaseUrl(),
                false, GenerateOptions.builder().build(), null, null);
        };
        return VisionOcrServices.create(properties, modelSupplier);
    }

    /** 附件落盘存储（本地磁盘 base-dir）。 */
    @Bean
    @ConditionalOnMissingBean(AttachmentFileStorage.class)
    public AttachmentFileStorage attachmentFileStorage(AttachmentProperties properties) {
        return new AttachmentFileStorage(properties.getBaseDir());
    }

    /**
     * 附件解析编排服务（唯一防御点）。解析器列表在此组装：文本 / Excel / Tika 文档 / 图片 OCR，
     * 各自扩展名不相交，顺序不影响正确性。
     */
    @Bean
    @ConditionalOnMissingBean(AttachmentParseService.class)
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
