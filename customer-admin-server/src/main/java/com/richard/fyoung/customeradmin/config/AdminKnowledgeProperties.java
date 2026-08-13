package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码知识库（P3-2 降级版）配置：{@code admin.knowledge.*}。
 *
 * <p>降级说明：完整形态依赖 starter 侧真实向量检索。本降级版用 DashScope 真实 Embedding（走既有
 * {@code ai_model_config} 模型配置体系拿 Key）+ MySQL 向量存储 + 应用层余弦相似度实现语义检索与问答，
 * 显式触发构建、按索引隔离；不静默降级回关键词。数据量万级以内为合理降级，升级路径是接真向量库。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.knowledge")
public class AdminKnowledgeProperties {

    /** DashScope Embedding 模型名。 */
    private String embeddingModel = "text-embedding-v3";

    /**
     * DashScope Embedding 原生端点 base-url。独立于 chat 模型的 base-url（chat 可能配成兼容模式地址，
     * 与 embeddings 原生路径不匹配），只从 {@code ai_model_config} 的 dashscope 行借用 API Key。
     */
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com";

    /** 向量维度（text-embedding-v3 支持 1024/768/512 等）。 */
    private int dimensions = 1024;

    /** 单次 Embedding 请求的最大文本条数（DashScope 批量上限，保守取 10）。 */
    private int batchSize = 10;

    /** 检索默认返回的 top-k 条数。 */
    private int defaultTopK = 5;

    /** 单个分块的最大字符数（超过按行边界继续切）。 */
    private int maxChunkChars = 1500;

    /** 单文件最大读取字节数（超过跳过，避免超大文件撑爆内存）。 */
    private long maxFileBytes = 512 * 1024;

    /**
     * 允许被索引的源码根目录白名单（源码路径必须落在其一之下，防路径穿越/越权读取宿主机任意目录）。
     * 默认只允许 admin 工作区（会话产物）；需索引外部仓库源码时在配置里追加其绝对路径根。
     */
    private List<String> allowedRoots = new ArrayList<>(List.of(RuntimeWorkDir.of("admin-workspace")));

    /**
     * 可被索引的文件扩展名白名单（其余一律跳过，避免把二进制/依赖产物灌进向量库）。
     */
    private List<String> includeExtensions = new ArrayList<>(List.of(
        "java", "kt", "groovy", "xml", "yml", "yaml", "properties", "sql",
        "ts", "tsx", "js", "vue", "md", "py", "go"));
}
