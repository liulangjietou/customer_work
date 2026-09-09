package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.retrieval;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.DocumentChunker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 检索质量基线：切分策略改动必须拿数字说话。
 *
 * <h3>为什么扫一组参数而不是钉一个</h3>
 * <p>第一版基线只跑了 {@code maxChars=300}，新旧两版都是满分——<b>照不出任何差异</b>，
 * 是一条恒真断言。诊断后发现原因是巧合：那个上限下旧策略的切点落在 316，
 * 而全部十个答案区间恰好都没跨过它。换成 200，旧策略立刻在 216 处劈开运费条款
 * （答案区间 {@code [187,236]}）。</p>
 *
 * <p>这说明「哪个参数能照出问题」取决于语料与上限的相对关系，**由我挑一个数字来证明
 * 自己的改动有效，本质上是选择性报告**。因此改为扫一组上限，要求新版在<b>每一个</b>
 * 上限下都不劣于旧版，并且<b>至少有一个</b>上限下严格更好——前者防退化，后者防空转
 * （改了等于没改也会全绿）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class RetrievalQualityBaselineTest {

    /** 扫描的分片上限。跨度覆盖「语料段落远大于上限」到「上限接近段落长度」两种形态。 */
    private static final int[] MAX_CHARS_SWEEP = {150, 200, 250, 300};

    private static final int OVERLAP = 120;
    private static final int TOP_K = 3;

    @Test
    @DisplayName("新切分在每个上限下都不劣于旧版，且至少一处严格更好")
    void currentChunkerIsNotWorseAndSomewhereBetter() {
        List<String> lines = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        boolean improvedSomewhere = false;

        for (int maxChars : MAX_CHARS_SWEEP) {
            RetrievalQualityHarness.Report legacy = RetrievalQualityHarness.evaluate(
                KnowledgeCorpus.content(), LegacyChunker::chunk,
                maxChars, TOP_K, KnowledgeCorpus.cases());
            RetrievalQualityHarness.Report current = RetrievalQualityHarness.evaluate(
                KnowledgeCorpus.content(), (c, m) -> DocumentChunker.chunk(c, m, OVERLAP),
                maxChars, TOP_K, KnowledgeCorpus.cases());

            lines.add(String.format("  maxChars=%-4d 旧[%s]  新[%s]", maxChars, legacy, current));

            if (current.recall() < legacy.recall()) {
                regressions.add("maxChars=" + maxChars + " 召回率下降："
                    + legacy.recall() + " → " + current.recall());
            }
            if (current.intactRate() < legacy.intactRate()) {
                regressions.add("maxChars=" + maxChars + " 完整覆盖率下降："
                    + legacy.intactRate() + " → " + current.intactRate());
            }
            if (current.recall() > legacy.recall() || current.intactRate() > legacy.intactRate()) {
                improvedSomewhere = true;
            }
        }

        System.out.println("[检索质量基线] topK=" + TOP_K + " overlap=" + OVERLAP + "\n"
            + String.join("\n", lines));

        if (!regressions.isEmpty()) {
            fail("切分改动让检索质量下降：\n" + String.join("\n", regressions)
                + "\n（这类退化不会以任何报错的形式出现，只表现为答得不准）");
        }
        assertTrue(improvedSomewhere,
            "新旧两版在所有上限下表现完全一致——要么改动没起作用，要么这组语料照不出它。\n"
                + "别把这条当成通过：基线的价值在于能分辨，全同意味着它此刻什么也没在守。");
    }

    /**
     * 钉住那个已知的劈开点。
     *
     * <p>上面那条是「整体不劣化」，方向对但粒度粗；这一条锁定具体缺陷本身，
     * 让人在读测试时就能看到它到底修的是什么。</p>
     */
    @Test
    @DisplayName("旧版会把运费条款劈成两半，新版不会")
    void freightClauseIsNoLongerSplit() {
        String content = KnowledgeCorpus.content();
        int maxChars = 200;

        assertTrue(isSplit(content, LegacyChunker.chunk(content, maxChars),
                KnowledgeCorpus.A_FREIGHT_RULE),
            "前提不成立了：旧算法在 maxChars=" + maxChars + " 下已不再劈开运费条款，"
                + "这条用例失去意义，应重新挑一个能复现的参数或语料");

        assertTrue(!isSplit(content, DocumentChunker.chunk(content, maxChars, OVERLAP),
                KnowledgeCorpus.A_FREIGHT_RULE),
            "新算法仍把运费条款劈成两半：检索照样能返回结果，但那段话没有结论，"
                + "模型据此给出的答复看起来很像回事");
    }

    /** 答案片段是否没有任何一个分片能完整容纳（即被切点劈开）。 */
    private boolean isSplit(String content, List<String> chunks, String answer) {
        return chunks.stream().noneMatch(c -> c.contains(answer));
    }
}
