package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersion;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBaseVersionDocument;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentChunk;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionDocumentMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseVersionMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentChunkMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeDocumentRevisionMapper;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeVersionDO;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识投影测试。
 *
 * <p><b>守的是什么</b>：这一步是把后台知识库接到客服端的唯一通路。它出问题的表现是
 * "后台看着一切正常、客服端查不到"或"查到的是上一版"——两边各自都不报错，极难排查。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeProjectionServiceTest {

    private KnowledgeProjectionGatewayProvider gatewayProvider;
    private KnowledgeChunkMapper cwChunkMapper;
    private KnowledgeVersionMapper cwVersionMapper;
    private AiKnowledgeBaseMapper kbMapper;
    private AiKnowledgeBaseVersionMapper versionMapper;
    private AiKnowledgeBaseVersionDocumentMapper memberMapper;
    private AiKnowledgeDocumentRevisionMapper revisionMapper;
    private AiKnowledgeDocumentChunkMapper chunkMapper;
    private KnowledgeProjectionService service;

    @BeforeEach
    void setUp() {
        gatewayProvider = mock(KnowledgeProjectionGatewayProvider.class);
        cwChunkMapper = mock(KnowledgeChunkMapper.class);
        cwVersionMapper = mock(KnowledgeVersionMapper.class);
        when(gatewayProvider.get())
            .thenReturn(new KnowledgeProjectionGateway(cwChunkMapper, cwVersionMapper));

        kbMapper = mock(AiKnowledgeBaseMapper.class);
        versionMapper = mock(AiKnowledgeBaseVersionMapper.class);
        memberMapper = mock(AiKnowledgeBaseVersionDocumentMapper.class);
        revisionMapper = mock(AiKnowledgeDocumentRevisionMapper.class);
        chunkMapper = mock(AiKnowledgeDocumentChunkMapper.class);

        AiKnowledgeBase kb = new AiKnowledgeBase();
        kb.setId(7L);
        kb.setKbName("售后FAQ");
        when(kbMapper.selectById(7L)).thenReturn(kb);

        AiKnowledgeBaseVersion version = new AiKnowledgeBaseVersion();
        version.setId(70L);
        version.setKnowledgeBaseId(7L);
        version.setTopN(5);
        version.setScoreThreshold(new BigDecimal("0.25"));
        when(versionMapper.selectById(70L)).thenReturn(version);

        AiKnowledgeBaseVersionDocument member = new AiKnowledgeBaseVersionDocument();
        member.setKnowledgeBaseVersionId(70L);
        member.setDocumentRevisionId(700L);
        member.setExternalId("doc-a");
        when(memberMapper.selectList(any())).thenReturn(List.of(member));

        AiKnowledgeDocumentRevision revision = new AiKnowledgeDocumentRevision();
        revision.setId(700L);
        revision.setAclMode("PUBLIC");
        when(revisionMapper.selectBatchIds(any())).thenReturn(List.of(revision));

        service = new KnowledgeProjectionService(gatewayProvider, kbMapper, versionMapper,
            memberMapper, revisionMapper, chunkMapper, new ObjectMapper());
    }

    private AiKnowledgeDocumentChunk sourceChunk(long id, String embeddingJson) {
        AiKnowledgeDocumentChunk c = new AiKnowledgeDocumentChunk();
        c.setId(id);
        c.setDocumentRevisionId(700L);
        c.setChunkIndex(0);
        c.setContent("七天无理由从签收次日算起");
        c.setEmbedding(embeddingJson);
        return c;
    }

    /**
     * 整版替换：先清场再整批写入。
     *
     * <p>知识库版本是不可变快照，投影出来就该与后台那一版逐字一致。不清场的话，
     * 上一版被删掉的文档会永远留在客服端——而两边看各自都"正常"。</p>
     */
    @Test
    @DisplayName("投影前先清空该版本的旧分片")
    void clearsVersionBeforeWriting() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(sourceChunk(1L, "[1.0,0.0]")));

        service.project(7L, 70L);

        verify(cwChunkMapper).deleteByVersion(70L);
    }

    @Test
    @DisplayName("向量从 JSON 转成定长 float32 落库")
    void convertsVectorToBinary() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(sourceChunk(1L, "[1.0,0.0]")));

        service.project(7L, 70L);

        ArgumentCaptor<KnowledgeChunkDO> captor = ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(cwChunkMapper).insert(captor.capture());
        KnowledgeChunkDO written = captor.getValue();
        assertArrayEquals(VectorCodec.encode(new float[]{1.0f, 0.0f}), written.getEmbedding(),
            "向量格式转换要在投影这一步完成——放到检索时每次都转，这次优化就白做了");
        assertEquals(2, written.getDimensions());
        assertEquals("PUBLIC", written.getAclMode(), "ACL 要随分片冗余，检索端没有后台的修订表可 JOIN");
        assertEquals("doc-a", written.getExternalId(), "来源标识要带过去，供回答溯源");
    }

    /**
     * 单条坏数据不该让整次投影失败，但必须留痕。
     *
     * <p>否则"知识库少了几条"没人能查出原因。</p>
     */
    @Test
    @DisplayName("向量缺失或解析失败的分片被跳过，不中断整次投影")
    void skipsChunksWithoutUsableVector() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            sourceChunk(1L, "[1.0,0.0]"),
            sourceChunk(2L, null),
            sourceChunk(3L, "not-a-vector")));

        int written = service.project(7L, 70L);

        assertEquals(1, written, "只有可用向量的那条被写入");
        verify(cwChunkMapper, times(1)).insert(any(KnowledgeChunkDO.class));
    }

    @Test
    @DisplayName("版本投影记录带出检索参数与实测维度")
    void writesVersionProjection() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(sourceChunk(1L, "[1.0,0.0,0.5]")));
        when(cwVersionMapper.selectOne(any())).thenReturn(null);

        service.project(7L, 70L);

        ArgumentCaptor<KnowledgeVersionDO> captor = ArgumentCaptor.forClass(KnowledgeVersionDO.class);
        verify(cwVersionMapper).insert(captor.capture());
        KnowledgeVersionDO row = captor.getValue();
        assertEquals(70L, row.getKbVersionId());
        assertEquals("售后FAQ", row.getKbName());
        assertEquals(5, row.getTopN());
        assertEquals(0, new BigDecimal("0.25").compareTo(row.getScoreThreshold()));
        assertEquals(3, row.getDimensions(), "维度取本次投影的实测值——它决定 C 端拿查询向量来比时能不能比");
        assertEquals(1, row.getChunkCount());
    }

    /** 重复投影同一版本时更新而不是插入第二条。 */
    @Test
    @DisplayName("重复投影更新既有版本记录")
    void reprojectionUpdatesExistingVersionRow() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(sourceChunk(1L, "[1.0,0.0]")));
        KnowledgeVersionDO existing = new KnowledgeVersionDO();
        existing.setId(1L);
        existing.setKbVersionId(70L);
        existing.setCreatedAtMs(1L);
        when(cwVersionMapper.selectOne(any())).thenReturn(existing);

        service.project(7L, 70L);

        verify(cwVersionMapper).updateById(any(KnowledgeVersionDO.class));
        verify(cwVersionMapper, times(0)).insert(any(KnowledgeVersionDO.class));
    }

    @Test
    @DisplayName("版本不属于该知识库时拒绝投影")
    void rejectsMismatchedVersion() {
        AiKnowledgeBaseVersion other = new AiKnowledgeBaseVersion();
        other.setId(71L);
        other.setKnowledgeBaseId(999L);
        when(versionMapper.selectById(71L)).thenReturn(other);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.project(7L, 71L));
        verify(gatewayProvider, times(0)).get();
    }
}
