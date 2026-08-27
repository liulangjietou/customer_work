package com.richard.fyoung.customeradmin.workspace.knowledge.service;

import com.richard.fyoung.customerwork.core.model.ModelResponses;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminKnowledgeProperties;
import com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.knowledge.dto.KnowledgeAskResponse;
import com.richard.fyoung.customeradmin.workspace.knowledge.dto.KnowledgeSearchHit;
import com.richard.fyoung.customeradmin.workspace.knowledge.entity.AiCodeKnowledgeChunk;
import com.richard.fyoung.customeradmin.workspace.knowledge.entity.AiCodeKnowledgeIndex;
import com.richard.fyoung.customeradmin.workspace.knowledge.mapper.AiCodeKnowledgeChunkMapper;
import com.richard.fyoung.customeradmin.workspace.knowledge.mapper.AiCodeKnowledgeIndexMapper;
import com.richard.fyoung.customerwork.data.knowledge.CodeChunker;
import com.richard.fyoung.customerwork.data.knowledge.VectorMath;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 代码知识库服务（P3-2 降级版）：显式触发建索引（扫描源码 → 类/方法级切块 → DashScope 真实 Embedding →
 * 向量入库），语义 top-k 检索与检索增强问答（应用层余弦相似度）。降级差异见 {@link AdminKnowledgeProperties}。
 *
 * <h3>关键约束</h3>
 * <ul>
 *   <li>源码路径必须落在 {@code admin.knowledge.allowed-roots} 白名单下（防路径穿越/越权读盘）；</li>
 *   <li>Embedding Key 缺失 fast fail（{@link ResultCode#KNOWLEDGE_EMBEDDING_NOT_CONFIGURED}），不静默降级回关键词；</li>
 *   <li>构建显式触发、进度可查（索引行 status + chunk_count 增量更新），不做自动监听。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    /** 建索引时跳过的目录（依赖产物/版本控制/IDE 目录），避免灌入无关内容。 */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "dist", "build", ".idea", ".gradle", "out", ".mvn");
    /** 检索命中片段展示上限。 */
    private static final int SNIPPET_MAX_CHARS = 800;
    /** 问答一次性模型调用超时：需小于前端 LLM_TIMEOUT_MS(180s)，保证超时时前端收到结构化错误而非 axios 超时。 */
    private static final long ASK_TIMEOUT_SECONDS = 170;
    /** 问答系统提示词：限定角色 + 抑制幻觉（检索片段里没有依据的就明说不知道，不编造）。 */
    private static final String ASK_SYSTEM_PROMPT = "你是一个知识库检索专家和助手，不知道的就回答不知道。";
    /**
     * 构建池与查询池分离：建索引是分钟级长任务（全量扫描 + Embedding），与 search/ask 共池时并发构建
     * 会把查询请求饿死。构建池保持小（限制并发构建数与对 Embedding API 的压力），查询池独立承载
     * 轻量的检索/问答；均为 daemon 线程，不阻塞进程退出（与 GitAssistantService 同手法）。
     */
    private static final ExecutorService KNOWLEDGE_BUILD_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "knowledge-build-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService KNOWLEDGE_QUERY_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "knowledge-query-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final AiCodeKnowledgeIndexMapper indexMapper;
    private final AiCodeKnowledgeChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final AdminKnowledgeProperties properties;
    private final AdminModelFactory modelFactory;
    private final ModelConfigAccess modelConfigAccess;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AiCodingAuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeService(AiCodeKnowledgeIndexMapper indexMapper, AiCodeKnowledgeChunkMapper chunkMapper,
                            EmbeddingClient embeddingClient, AdminKnowledgeProperties properties,
                            AdminModelFactory modelFactory, ModelConfigAccess modelConfigAccess,
                            AesGcmCryptoUtil cryptoUtil, AiCodingAuditService auditService) {
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.modelFactory = modelFactory;
        this.modelConfigAccess = modelConfigAccess;
        this.cryptoUtil = cryptoUtil;
        this.auditService = auditService;
    }

    // ---------------------- 索引管理 ----------------------

    /**
     * 触发构建/重建索引：请求线程内校验路径 + 落库 BUILDING 行 + 创建审计条目（操作人取自 Sa-Token），
     * 重活（扫描/切块/Embedding/入库）派到后台线程执行，接口立即返回索引ID供前端轮询进度。
     */
    public Long buildIndex(String indexName, String sourcePath) {
        Path root = resolveAndValidatePath(sourcePath);
        AiCodeKnowledgeIndex index = upsertBuildingIndex(indexName, sourcePath);
        Long indexId = index.getId();
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.KNOWLEDGE_INDEX, "-", indexName);
        String tenantId = TenantContext.get();
        KNOWLEDGE_BUILD_EXECUTOR.submit(() -> TenantContext.runWith(tenantId, () -> {
                try {
                    int count = doBuild(indexId, root);
                    markReady(indexId, count);
                    auditService.finish(audit, (String) null);
                    log.info("[knowledge] index built, indexId={}, name={}, chunks={}", indexId, indexName, count);
                } catch (Exception e) {
                    markFailed(indexId, e);
                    auditService.finish(audit,
                        e instanceof BizException be ? be.getResultCode().name() : "KNOWLEDGE_INDEX_FAILED");
                    log.error("[knowledge] index build failed, code={}, indexId={}, name={}",
                        "KNOWLEDGE-INDEX-FAIL", indexId, indexName, e);
                }
            }));
        return indexId;
    }

    /** 索引列表（全量，按创建时间倒序；数据量小，不分页）。 */
    public List<AiCodeKnowledgeIndex> listIndexes() {
        return indexMapper.selectList(new LambdaQueryWrapper<AiCodeKnowledgeIndex>()
            .orderByDesc(AiCodeKnowledgeIndex::getCreateTime));
    }

    /** 查单个索引（含 status/chunk_count，供进度轮询）。 */
    public AiCodeKnowledgeIndex getIndex(Long indexId) {
        AiCodeKnowledgeIndex index = indexMapper.selectById(indexId);
        if (index == null) {
            throw new BizException(ResultCode.KNOWLEDGE_INDEX_NOT_FOUND);
        }
        return index;
    }

    /** 删除索引及其全部分块。 */
    public void deleteIndex(Long indexId) {
        getIndex(indexId);
        chunkMapper.delete(new LambdaQueryWrapper<AiCodeKnowledgeChunk>().eq(AiCodeKnowledgeChunk::getIndexId, indexId));
        indexMapper.deleteById(indexId);
        log.info("[knowledge] index deleted, indexId={}", indexId);
    }

    // ---------------------- 检索 / 问答 ----------------------

    /** 语义检索 top-k（应用层余弦相似度）。异步执行，Embedding/相似度不占 Tomcat 线程。 */
    public CompletableFuture<List<KnowledgeSearchHit>> search(Long indexId, String query, Integer topK) {
        requireReadyIndex(indexId);
        String tenantId = TenantContext.get();
        return CompletableFuture.supplyAsync(
            () -> TenantContext.callWith(tenantId, () -> searchInternal(indexId, query, topK)),
            KNOWLEDGE_QUERY_EXECUTOR);
    }

    /** 检索增强问答：检索 top-k → 拼上下文 → 一次性模型作答，回答带出处。 */
    public CompletableFuture<KnowledgeAskResponse> ask(Long indexId, String question, Integer topK) {
        requireReadyIndex(indexId);
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.KNOWLEDGE_ASK, "-", String.valueOf(indexId));
        String tenantId = TenantContext.get();
        return CompletableFuture.supplyAsync(() -> TenantContext.callWith(tenantId, () -> {
                List<KnowledgeSearchHit> hits = searchInternal(indexId, question, topK);
                if (CollectionUtils.isEmpty(hits)) {
                    return new KnowledgeAskResponse(
                        "知识库中未检索到与问题相关的代码片段，请先构建/更新索引或调整提问。", List.of());
                }
                Model model = resolveDefaultChatModel();
                String answer = callModelOnce(model, buildAskPrompt(question, hits));
                return new KnowledgeAskResponse(answer, hits);
            }), KNOWLEDGE_QUERY_EXECUTOR)
            .whenComplete((result, error) ->
                TenantContext.runWith(tenantId, () -> auditService.finish(audit, error)));
    }

    /**
     * 检索/问答前置校验：索引必须存在且 READY——BUILDING 时分块只入了一半，检索会返回半成品结果；
     * FAILED 时数据不完整不可信。两种非就绪态统一 fast fail（复用 40031，消息里带上实际状态便于排查）。
     */
    private AiCodeKnowledgeIndex requireReadyIndex(Long indexId) {
        AiCodeKnowledgeIndex index = getIndex(indexId);
        if (!AiCodeKnowledgeIndex.STATUS_READY.equals(index.getStatus())) {
            throw new BizException(ResultCode.KNOWLEDGE_INDEX_BUILDING,
                "索引未就绪（当前状态: " + index.getStatus() + "），请等待构建完成或重建后再检索");
        }
        return index;
    }

    // ---------------------- 内部：构建 ----------------------

    private int doBuild(Long indexId, Path root) throws Exception {
        // 重建幂等：先清掉旧分块
        chunkMapper.delete(new LambdaQueryWrapper<AiCodeKnowledgeChunk>().eq(AiCodeKnowledgeChunk::getIndexId, indexId));
        List<PendingChunk> pending = collectChunks(root);
        if (pending.isEmpty()) {
            return 0;
        }
        int dims = embeddingClient.dimensions();
        int batchSize = Math.max(1, properties.getBatchSize());
        int persisted = 0;
        for (int start = 0; start < pending.size(); start += batchSize) {
            List<PendingChunk> batch = pending.subList(start, Math.min(pending.size(), start + batchSize));
            List<float[]> vectors = embeddingClient.embedDocuments(
                batch.stream().map(PendingChunk::content).collect(Collectors.toList()));
            for (int i = 0; i < batch.size(); i++) {
                persistChunk(indexId, batch.get(i), vectors.get(i), dims);
            }
            persisted += batch.size();
            updateProgress(indexId, persisted);
        }
        return persisted;
    }

    /**
     * 扫描源码目录/文件，切块成待 Embedding 的分块列表。
     *
     * <p><b>符号链接防御（与 {@link #resolveAndValidatePath} 双保险）</b>：{@code walkFileTree} 默认不跟随
     * 目录软链，但文件软链仍会以"文件"身份进入 {@code visitFile}，而 {@code Files.readString} 会跟随它——
     * Agent 可在会话目录建 {@code x.java -> /etc/passwd} 之类软链把宿主机任意文件读进库。会话产物目录里
     * 不存在合法软链场景，遍历时逐个 {@link Files#isSymbolicLink} 判断直接跳过是最干净的语义。
     * 包级可见，便于软链跳过单测。</p>
     */
    List<PendingChunk> collectChunks(Path root) throws Exception {
        List<PendingChunk> pending = new ArrayList<>();
        boolean rootIsDir = Files.isDirectory(root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return SKIP_DIRS.contains(dir.getFileName().toString())
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(file)) {
                    log.info("[knowledge] symlink skipped during index build, file={}", file);
                    return FileVisitResult.CONTINUE;
                }
                String ext = extensionOf(file.getFileName().toString());
                if (!properties.getIncludeExtensions().contains(ext) || attrs.size() > properties.getMaxFileBytes()) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = rootIsDir ? root.relativize(file).toString() : file.getFileName().toString();
                String content = readTextOrNull(file);
                if (content == null) {
                    return FileVisitResult.CONTINUE;
                }
                for (CodeChunker.Chunk chunk : CodeChunker.chunk(relative, content, properties.getMaxChunkChars())) {
                    pending.add(new PendingChunk(relative, ext, chunk.index(), chunk.symbol(), chunk.content()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return pending;
    }

    private void persistChunk(Long indexId, PendingChunk pending, float[] vector, int dims) throws Exception {
        AiCodeKnowledgeChunk chunk = new AiCodeKnowledgeChunk();
        chunk.setIndexId(indexId);
        chunk.setSourcePath(pending.path());
        chunk.setChunkIndex(pending.chunkIndex());
        chunk.setSymbol(pending.symbol());
        chunk.setLang(pending.lang());
        chunk.setContent(pending.content());
        chunk.setEmbedding(objectMapper.writeValueAsString(vector));
        chunk.setDimensions(dims);
        chunkMapper.insert(chunk);
    }

    // ---------------------- 内部：检索 ----------------------

    private List<KnowledgeSearchHit> searchInternal(Long indexId, String query, Integer topK) {
        if (!StringUtils.hasText(query)) {
            throw new BizException(ResultCode.PARAM_INVALID, "查询内容不能为空");
        }
        float[] queryVector = embeddingClient.embedQuery(query);
        List<AiCodeKnowledgeChunk> chunks = chunkMapper.selectList(
            new LambdaQueryWrapper<AiCodeKnowledgeChunk>().eq(AiCodeKnowledgeChunk::getIndexId, indexId));
        if (CollectionUtils.isEmpty(chunks)) {
            return List.of();
        }
        int limit = topK != null && topK > 0 ? topK : properties.getDefaultTopK();
        return chunks.stream()
            .map(chunk -> new ScoredChunk(chunk, VectorMath.cosine(queryVector, parseVector(chunk.getEmbedding()))))
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .limit(limit)
            .map(scored -> new KnowledgeSearchHit(
                scored.chunk().getSourcePath(), scored.chunk().getSymbol(), scored.chunk().getLang(),
                scored.chunk().getChunkIndex(), scored.score(), snippet(scored.chunk().getContent())))
            .collect(Collectors.toList());
    }

    private float[] parseVector(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            log.error("[knowledge] parse embedding vector failed, code={}", "KNOWLEDGE-VEC-PARSE", e);
            return new float[0];
        }
    }

    // ---------------------- 内部：问答模型调用 ----------------------

    /** 组装检索增强问答提示词：注入命中片段作为上下文，要求据代码作答并标注出处，信息不足时明说。 */
    private String buildAskPrompt(String question, List<KnowledgeSearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深代码助手。请仅依据下面提供的代码片段回答问题；信息不足时明确说明，不要编造。")
            .append("回答末尾用「参考来源：」列出所依据的文件路径与符号。\n\n[代码片段]\n");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchHit hit = hits.get(i);
            sb.append("片段").append(i + 1).append(" 【").append(hit.sourcePath());
            if (StringUtils.hasText(hit.symbol())) {
                sb.append('#').append(hit.symbol());
            }
            sb.append("】\n").append(hit.snippet()).append("\n\n");
        }
        sb.append("[问题]\n").append(question);
        return sb.toString();
    }

    /** 解析可用的默认对话模型（isDefault 优先，否则首个启用行）构建 chat Model。 */
    private Model resolveDefaultChatModel() {
        List<AiModelConfig> candidates = modelConfigAccess.listPreferredEnabled(null);
        if (CollectionUtils.isEmpty(candidates)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "未配置可用的对话模型用于知识库问答");
        }
        AiModelConfig config = candidates.get(0);
        // 窗口取资产登记值而不是让框架按模型名猜：第三方模型猜不出来会返回 0（未知），下游会当成「窗口为零」
        return modelFactory.buildModelWithWindow(config.getProvider(), config.getBaseUrl(),
            cryptoUtil.decrypt(config.getApiKey()), config.getModel(),
            modelFactory.resolveDeclaredContextWindow(config.getId()));
    }

    /** 一次性模型调用（与 GitAssistantService/CollaborativeCodingService 同手法），带知识库问答系统提示词。 */
    private String callModelOnce(Model model, String prompt) {
        Msg systemMsg = Msg.builder().role(MsgRole.SYSTEM).name("system")
            .content(TextBlock.builder().text(ASK_SYSTEM_PROMPT).build()).build();
        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(prompt).build()).build();
        List<ChatResponse> responses = model.stream(List.of(systemMsg, userMsg), List.of(), GenerateOptions.builder().build())
            .collectList()
            .block(Duration.ofSeconds(ASK_TIMEOUT_SECONDS));
        if (CollectionUtils.isEmpty(responses)) {
            throw new BizException(ResultCode.GIT_ASSISTANT_AI_FAILED, "模型未返回任何内容");
        }
        String text = ModelResponses.text(responses);
        if (!StringUtils.hasText(text)) {
            throw new BizException(ResultCode.GIT_ASSISTANT_AI_FAILED, "模型返回内容为空");
        }
        return text.trim();
    }

    // ---------------------- 内部：索引行状态 ----------------------

    private AiCodeKnowledgeIndex upsertBuildingIndex(String indexName, String sourcePath) {
        AiCodeKnowledgeIndex existing = indexMapper.selectOne(new LambdaQueryWrapper<AiCodeKnowledgeIndex>()
            .eq(AiCodeKnowledgeIndex::getIndexName, indexName));
        if (existing != null && AiCodeKnowledgeIndex.STATUS_BUILDING.equals(existing.getStatus())) {
            throw new BizException(ResultCode.KNOWLEDGE_INDEX_BUILDING);
        }
        AiCodeKnowledgeIndex index = existing != null ? existing : new AiCodeKnowledgeIndex();
        index.setIndexName(indexName);
        index.setSourcePath(sourcePath);
        index.setEmbeddingModel(embeddingClient.modelName());
        index.setDimensions(embeddingClient.dimensions());
        index.setChunkCount(0);
        index.setStatus(AiCodeKnowledgeIndex.STATUS_BUILDING);
        index.setMessage("构建中");
        if (existing != null) {
            indexMapper.updateById(index);
        } else {
            indexMapper.insert(index);
        }
        return index;
    }

    private void updateProgress(Long indexId, int chunkCount) {
        AiCodeKnowledgeIndex index = new AiCodeKnowledgeIndex();
        index.setId(indexId);
        index.setChunkCount(chunkCount);
        indexMapper.updateById(index);
    }

    private void markReady(Long indexId, int chunkCount) {
        AiCodeKnowledgeIndex index = new AiCodeKnowledgeIndex();
        index.setId(indexId);
        index.setChunkCount(chunkCount);
        index.setStatus(AiCodeKnowledgeIndex.STATUS_READY);
        index.setMessage("构建完成，共 " + chunkCount + " 个分块");
        indexMapper.updateById(index);
    }

    private void markFailed(Long indexId, Throwable error) {
        AiCodeKnowledgeIndex index = new AiCodeKnowledgeIndex();
        index.setId(indexId);
        index.setStatus(AiCodeKnowledgeIndex.STATUS_FAILED);
        String message = error.getMessage();
        index.setMessage("构建失败：" + (message == null ? error.getClass().getSimpleName() : message));
        indexMapper.updateById(index);
    }

    // ---------------------- 内部：路径 / 文件 ----------------------

    /**
     * 解析并校验源码路径：必须存在且落在 {@code allowed-roots} 白名单某个根之下，否则 fast fail。
     * 防止越权读取宿主机任意目录（该链路会遍历读文件内容）。
     *
     * <p><b>符号链接防御</b>：{@code normalize()} 只做字面归一、不解析软链——Agent 可在会话目录里放一个
     * 指向白名单外的目录软链绕过 startsWith 校验。这里目标路径与白名单根都用 {@link Path#toRealPath}
     * 解析到物理真实路径后再比对（目标不存在时 toRealPath 抛异常，一并覆盖存在性校验）；文件级软链
     * 由 {@link #collectChunks} 在遍历时逐个跳过（双保险）。包级可见，便于路径安全单测。
     */
    Path resolveAndValidatePath(String sourcePath) {
        Path target;
        try {
            // toRealPath 解析全部软链且要求路径存在（LinkOption 默认跟随），得到物理真实路径
            target = Path.of(sourcePath).toRealPath();
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "源码路径不存在或不可解析: " + sourcePath);
        }
        boolean allowed = properties.getAllowedRoots().stream()
            .map(this::realPathOrNull)
            .anyMatch(root -> root != null && target.startsWith(root));
        if (!allowed) {
            log.error("[knowledge] source path not allowed, code={}, path={}", "KNOWLEDGE-PATH-BLOCKED", target);
            throw new BizException(ResultCode.KNOWLEDGE_PATH_NOT_ALLOWED);
        }
        return target;
    }

    /** 白名单根的物理真实路径；根目录不存在/不可解析时返回 null（该根视为不匹配，不阻断其它根）。 */
    private Path realPathOrNull(String root) {
        try {
            return Path.of(root).toRealPath();
        } catch (Exception e) {
            return null;
        }
    }

    private String readTextOrNull(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return null;
        } catch (Exception e) {
            log.error("[knowledge] read source file failed, code={}, file={}", "KNOWLEDGE-READ-FAIL", file, e);
            return null;
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_MAX_CHARS ? content : content.substring(0, SNIPPET_MAX_CHARS) + " ...";
    }

    /** 待 Embedding 的分块（内存中间态）。包级可见，便于 {@link #collectChunks} 的软链跳过单测断言。 */
    record PendingChunk(String path, String lang, int chunkIndex, String symbol, String content) {
    }

    /** 打分后的分块（检索排序用）。 */
    private record ScoredChunk(AiCodeKnowledgeChunk chunk, double score) {
    }
}
