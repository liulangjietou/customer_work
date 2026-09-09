package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.retrieval;

import com.richard.fyoung.customerwork.data.knowledge.vector.InMemoryVectorStore;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorMatch;
import com.richard.fyoung.customerwork.data.knowledge.vector.VectorQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 检索质量评估装置：把「切分策略 + 召回」这一段单独量出来。
 *
 * <h3>为什么要有它</h3>
 * <p>批次三的验收标准当初就写着「不能只是测试绿，要有一组固定 query 的召回率对比」，
 * 而至今没有任何检索质量指标：`KnowledgeGap` 记的是「问了没答上来」（未命中埋点），
 * 评测链路是端到端 LLM-judge——检索差一点会被模型的兜底能力掩盖，
 * 于是换 embedding 模型、调分块大小、改 topK 全凭感觉，改好改坏都说不清。</p>
 *
 * <h3>三个刻意的设计</h3>
 * <ol>
 *   <li><b>答案按原文字符区间标注，不按 chunk id</b>。切分策略一变 chunk 边界就全变，
 *       按 chunk id 标注的话每改一次切分都要重标一遍，基线也就失去了可比性——
 *       而「改切分前后哪个更好」正是它唯一要回答的问题。区间由锚点子串定位，
 *       改语料也不必手数字符。</li>
 *   <li><b>embedding 用确定性桩而非真实模型</b>。要量的是切分与召回策略，
 *       真实模型会把结论变成「那天那个模型的表现」，既不可重复，也分不清是谁的功劳。
 *       桩用字符 bigram 词袋 + L2 归一，余弦≈bigram 重合度——中文里 bigram 近似于词，
 *       足以反映「答案被切碎后这一段还剩多少线索」。</li>
 *   <li><b>同时量「召回到」与「完整覆盖」</b>。只看 Recall 会漏掉无重叠切分最典型的伤害：
 *       答案被切点劈成两半时，topK 里那半句照样算召回成功，而模型拿到的是残缺信息。
 *       {@link Report#intactRate} 专门盯这个。</li>
 * </ol>
 *
 * @author owlzhangfq@gmail.com
 */
final class RetrievalQualityHarness {

    /** 桩向量维度：bigram 哈希映射到这么多个桶。 */
    private static final int DIMENSIONS = 512;

    private static final String NAMESPACE = "eval";
    private static final String PARTITION = "p1";

    private RetrievalQualityHarness() {
    }

    /**
     * 一条评估用例。
     *
     * @param question   用户会怎么问
     * @param answerText 正确答案在原文里的**原样**片段；它在原文中的区间即判定标准
     */
    record Case(String question, String answerText) {
    }

    /**
     * 一次评估的结果。
     *
     * @param recall     topK 里至少有一个 chunk 与答案区间有重叠的比例
     * @param intactRate 答案区间**完整**落在某一个 chunk 里的比例（未被切点劈开）
     */
    record Report(double recall, double intactRate, int total, List<String> misses) {

        @Override
        public String toString() {
            return String.format("Recall@K=%.3f 完整覆盖=%.3f (共 %d 条)%s",
                recall, intactRate, total, misses.isEmpty() ? "" : " 未召回：" + misses);
        }
    }

    /**
     * 跑一遍评估。
     *
     * @param content  语料原文
     * @param chunker  待评估的切分策略：(原文, 上限) -> 分片列表
     * @param maxChars 分片字符上限
     * @param topK     召回条数
     */
    static Report evaluate(String content,
                           BiFunction<String, Integer, List<String>> chunker,
                           int maxChars,
                           int topK,
                           List<Case> cases) {
        List<String> chunks = chunker.apply(content, maxChars);
        Map<String, int[]> spanByChunkId = locate(content, chunks);

        InMemoryVectorStore store = new InMemoryVectorStore();
        store.clear(NAMESPACE);
        int i = 0;
        for (String chunk : chunks) {
            store.upsert(NAMESPACE, "c" + (i++), PARTITION, embed(chunk));
        }

        int recalled = 0;
        int intact = 0;
        List<String> misses = new ArrayList<>();
        for (Case c : cases) {
            int[] answer = spanOf(content, c.answerText());
            List<VectorMatch> hits = store.search(new VectorQuery(
                NAMESPACE, List.of(PARTITION), embed(c.question()), topK, 0d));

            boolean overlapped = false;
            boolean covered = false;
            for (VectorMatch hit : hits) {
                int[] span = spanByChunkId.get(hit.chunkId());
                if (span == null) {
                    continue;
                }
                if (span[0] < answer[1] && answer[0] < span[1]) {
                    overlapped = true;
                }
                if (span[0] <= answer[0] && answer[1] <= span[1]) {
                    covered = true;
                }
            }
            if (overlapped) {
                recalled++;
            } else {
                misses.add(c.question());
            }
            if (covered) {
                intact++;
            }
        }
        int total = cases.size();
        return new Report((double) recalled / total, (double) intact / total, total, misses);
    }

    /**
     * 把每个分片定位回原文区间。
     *
     * <p>顺序扫描而不是全文 {@code indexOf}：分片内容可能在原文里重复出现
     * （FAQ 语料尤其容易），从上一个分片的结束位置往后找才不会把它定位到前面去。
     * 定位不到的分片（切分策略改写了内容，例如去掉了段落间的空行）跳过而不是报错——
     * 那意味着这个分片不参与区间判定，只会让分数偏保守，不会造出虚高的结果。</p>
     */
    private static Map<String, int[]> locate(String content, List<String> chunks) {
        Map<String, int[]> spans = new LinkedHashMap<>();
        int cursor = 0;
        int i = 0;
        for (String chunk : chunks) {
            String id = "c" + (i++);
            int start = content.indexOf(chunk, cursor);
            if (start < 0) {
                start = content.indexOf(chunk);
            }
            if (start < 0) {
                continue;
            }
            spans.put(id, new int[]{start, start + chunk.length()});
            cursor = start;
        }
        return spans;
    }

    private static int[] spanOf(String content, String answer) {
        int start = content.indexOf(answer);
        if (start < 0) {
            throw new IllegalArgumentException("答案片段不在语料里，用例写错了：" + answer);
        }
        return new int[]{start, start + answer.length()};
    }

    /**
     * 确定性桩向量：字符 bigram 词袋哈希 + L2 归一。
     *
     * <p>余弦相似度因此约等于两段文本的 bigram 重合度。它不是语义检索，
     * 但对「这一段还剩多少能对上问题的线索」这个问题给出的答案是稳定且可解释的。</p>
     */
    static float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        String normalized = text.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < normalized.length(); i++) {
            int bucket = Math.floorMod(normalized.substring(i, i + 2).hashCode(), DIMENSIONS);
            v[bucket] += 1f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= (float) norm;
            }
        }
        return v;
    }
}
