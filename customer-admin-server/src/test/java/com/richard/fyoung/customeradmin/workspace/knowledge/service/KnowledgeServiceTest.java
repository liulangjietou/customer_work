package com.richard.fyoung.customeradmin.workspace.knowledge.service;

import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminKnowledgeProperties;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.knowledge.dto.KnowledgeSearchHit;
import com.richard.fyoung.customerwork.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customeradmin.workspace.knowledge.entity.AiCodeKnowledgeChunk;
import com.richard.fyoung.customeradmin.workspace.knowledge.entity.AiCodeKnowledgeIndex;
import com.richard.fyoung.customeradmin.workspace.knowledge.mapper.AiCodeKnowledgeChunkMapper;
import com.richard.fyoung.customeradmin.workspace.knowledge.mapper.AiCodeKnowledgeIndexMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeService} 单测：应用层余弦相似度检索的排序与 top-k 截断（Embedding 以桩隔离）、
 * 索引就绪态前置校验（非 READY fast fail）、符号链接防御（文件软链跳过 + 目录软链路径校验拒绝）。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeServiceTest {

    private final AiCodeKnowledgeIndexMapper indexMapper = mock(AiCodeKnowledgeIndexMapper.class);
    private final AiCodeKnowledgeChunkMapper chunkMapper = mock(AiCodeKnowledgeChunkMapper.class);
    private final AiModelConfigMapper modelConfigMapper = mock(AiModelConfigMapper.class);
    private final AdminModelFactory modelFactory = mock(AdminModelFactory.class);
    private final AesGcmCryptoUtil cryptoUtil = mock(AesGcmCryptoUtil.class);
    private final AiCodingAuditService auditService = mock(AiCodingAuditService.class);

    /** 固定查询向量 {1,0}、二维的 Embedding 桩，完全离线。 */
    private final EmbeddingClient embeddingStub = new EmbeddingClient() {
        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return List.of();
        }

        @Override
        public float[] embedQuery(String text) {
            return new float[]{1f, 0f};
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public String modelName() {
            return "test-embedding";
        }
    };

    private KnowledgeService newService(AdminKnowledgeProperties properties) {
        return new KnowledgeService(indexMapper, chunkMapper, embeddingStub, properties,
            modelFactory, modelConfigMapper, cryptoUtil, auditService);
    }

    @Test
    void searchRanksByCosineAndAppliesTopK() throws Exception {
        AdminKnowledgeProperties properties = new AdminKnowledgeProperties();
        properties.setDefaultTopK(2);
        KnowledgeService service = newService(properties);

        AiCodeKnowledgeIndex index = new AiCodeKnowledgeIndex();
        index.setId(1L);
        index.setStatus(AiCodeKnowledgeIndex.STATUS_READY);
        when(indexMapper.selectById(1L)).thenReturn(index);

        List<AiCodeKnowledgeChunk> chunks = new ArrayList<>();
        chunks.add(chunk("A.java", "add", "[1.0,0.0]"));   // cos=1.0（最相关）
        chunks.add(chunk("B.java", "sub", "[0.0,1.0]"));   // cos=0.0（不相关）
        chunks.add(chunk("C.java", "mul", "[0.7071,0.7071]")); // cos≈0.707
        when(chunkMapper.selectList(any())).thenReturn(chunks);

        List<KnowledgeSearchHit> hits = service.search(1L, "如何相加", null).get();

        assertEquals(2, hits.size(), "top-k=2 应只返回两条");
        assertEquals("A.java", hits.get(0).sourcePath());
        assertEquals("C.java", hits.get(1).sourcePath());
        assertTrue(hits.get(0).score() > hits.get(1).score(), "应按相似度降序");
        assertEquals(1.0d, hits.get(0).score(), 1e-4);
    }

    @Test
    void searchOnNonReadyIndexFastFails() {
        KnowledgeService service = newService(new AdminKnowledgeProperties());
        AiCodeKnowledgeIndex building = new AiCodeKnowledgeIndex();
        building.setId(2L);
        building.setStatus(AiCodeKnowledgeIndex.STATUS_BUILDING);
        when(indexMapper.selectById(2L)).thenReturn(building);

        BizException searchEx = assertThrows(BizException.class, () -> service.search(2L, "任意查询", null));
        assertEquals(ResultCode.KNOWLEDGE_INDEX_BUILDING, searchEx.getResultCode());
        BizException askEx = assertThrows(BizException.class, () -> service.ask(2L, "任意问题", null));
        assertEquals(ResultCode.KNOWLEDGE_INDEX_BUILDING, askEx.getResultCode());

        // FAILED 同样非就绪，统一拒绝
        AiCodeKnowledgeIndex failed = new AiCodeKnowledgeIndex();
        failed.setId(3L);
        failed.setStatus(AiCodeKnowledgeIndex.STATUS_FAILED);
        when(indexMapper.selectById(3L)).thenReturn(failed);
        BizException failedEx = assertThrows(BizException.class, () -> service.search(3L, "任意查询", null));
        assertEquals(ResultCode.KNOWLEDGE_INDEX_BUILDING, failedEx.getResultCode());
    }

    @Test
    void collectChunksSkipsSymlinkFiles(@TempDir Path tempDir) throws Exception {
        KnowledgeService service = newService(new AdminKnowledgeProperties());
        // 真实文件（应入库）+ 指向目录外敏感文件的软链（必须被跳过，否则宿主机任意文件会被读进库）
        Path realFile = tempDir.resolve("Real.java");
        Files.writeString(realFile, "public class Real { }\n");
        Path secret = Files.writeString(tempDir.getParent().resolve(tempDir.getFileName() + "-secret.java"),
            "public class Secret { }\n");
        Files.createSymbolicLink(tempDir.resolve("Link.java"), secret);

        List<KnowledgeService.PendingChunk> chunks = service.collectChunks(tempDir);

        assertTrue(chunks.stream().allMatch(c -> "Real.java".equals(c.path())),
            "only the real file should be chunked, got " + chunks);
        assertTrue(chunks.stream().noneMatch(c -> c.content().contains("Secret")),
            "symlink target content must never be ingested");
    }

    @Test
    void symlinkDirectoryOutsideAllowedRootIsRejected(@TempDir Path tempDir) throws Exception {
        // 白名单只放行 allowed/，在其下建指向 outside/ 的目录软链——normalize 校验会误放行，toRealPath 必须拒绝
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("Escape.java"), "public class Escape { }\n");
        Path link = Files.createSymbolicLink(allowed.resolve("link"), outside);

        AdminKnowledgeProperties properties = new AdminKnowledgeProperties();
        properties.setAllowedRoots(new ArrayList<>(List.of(allowed.toString())));
        KnowledgeService service = newService(properties);

        BizException ex = assertThrows(BizException.class, () -> service.resolveAndValidatePath(link.toString()));
        assertEquals(ResultCode.KNOWLEDGE_PATH_NOT_ALLOWED, ex.getResultCode());

        // 正例：白名单内的真实目录应放行（顺带覆盖 /tmp 本身是软链的 macOS 场景——两侧都走 toRealPath）
        assertEquals(allowed.toRealPath(), service.resolveAndValidatePath(allowed.toString()));
    }

    private AiCodeKnowledgeChunk chunk(String path, String symbol, String embeddingJson) {
        AiCodeKnowledgeChunk chunk = new AiCodeKnowledgeChunk();
        chunk.setIndexId(1L);
        chunk.setSourcePath(path);
        chunk.setSymbol(symbol);
        chunk.setLang("java");
        chunk.setChunkIndex(0);
        chunk.setContent("content of " + path);
        chunk.setEmbedding(embeddingJson);
        chunk.setDimensions(2);
        return chunk;
    }
}
