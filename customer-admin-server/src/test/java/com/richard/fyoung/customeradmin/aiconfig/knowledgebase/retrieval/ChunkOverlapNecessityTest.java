package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.retrieval;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.DocumentChunker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重叠不是白加的：句子对齐单独解决不了问题。
 *
 * <h3>为什么需要这条测试</h3>
 * <p>「超长段落按句子边界切」是个很自然的直觉，容易让人认为重叠是多余的成本
 * （它确实要多存约 17% 的分片与向量）。实测的结论相反，而且反直觉：
 * 在 {@code maxChars=200} 下<b>只做句子对齐，完整覆盖率仍是 0.900</b>，
 * 是重叠把它补到 1.000。</p>
 *
 * <p>原因在于评估语料里那条运费条款自身就带分号——
 * 「无理由退货的往返运费由买家承担<b>；</b>因商品质量问题……由平台承担。」——
 * 句子对齐于是<b>精准地切在了这条知识的中间</b>，把「谁承担」的两种情形拆散。
 * 一个知识点跨多个句子是常态，对齐保证的是「句子完整」而不是「语义完整」，
 * 两者并不等价。有人日后想省掉重叠时，这条测试会告诉他代价是什么。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ChunkOverlapNecessityTest {

    private static final int MAX_CHARS = 200;
    private static final int TOP_K = 3;

    @Test
    @DisplayName("仅句子对齐不足以避免知识被劈开，重叠才补上了那一档")
    void overlapCoversWhatSentenceAlignmentCannot() {
        RetrievalQualityHarness.Report alignedOnly = RetrievalQualityHarness.evaluate(
            KnowledgeCorpus.content(), (c, m) -> DocumentChunker.chunk(c, m, 0),
            MAX_CHARS, TOP_K, KnowledgeCorpus.cases());
        RetrievalQualityHarness.Report aligned = RetrievalQualityHarness.evaluate(
            KnowledgeCorpus.content(), (c, m) -> DocumentChunker.chunk(c, m, 120),
            MAX_CHARS, TOP_K, KnowledgeCorpus.cases());

        assertTrue(alignedOnly.intactRate() < 1.0,
            "前提不成立了：只做句子对齐已经能全覆盖，那么重叠的成本需要重新评估。"
                + "实际 " + alignedOnly);
        assertEquals(1.0, aligned.intactRate(), 1e-9,
            "加了重叠仍未能完整覆盖全部答案：" + aligned);
    }

    @Test
    @DisplayName("重叠带来的分片增量在可接受范围内")
    void overlapCostIsBounded() {
        int without = DocumentChunker.chunk(KnowledgeCorpus.content(), MAX_CHARS, 0).size();
        int with = DocumentChunker.chunk(KnowledgeCorpus.content(), MAX_CHARS, 120).size();

        assertTrue(with <= without * 1.5,
            "重叠让分片数增长超过 50%（" + without + " → " + with + "）——"
                + "存储、向量化开销与检索延迟都会跟着涨，overlap 相对 maxChars 可能配得过大");
    }
}
