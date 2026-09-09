package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识文档切分：把一篇文档切成可独立检索的片段。
 *
 * <h3>为什么它值得是一个独立的类</h3>
 * <p>切分质量直接决定检索质量的上限——召回不到的内容，后面的模型再强也补不回来。
 * 它此前是 {@link KnowledgeDocumentIndexer} 里的一个包内 static 方法，
 * 既无法单独评估，也没有地方承载「为什么这样切」的判断。</p>
 *
 * <h3>三层策略</h3>
 * <ol>
 *   <li><b>先按空行切段落</b>：段落是作者给出的天然语义边界，比任何长度启发式都准。</li>
 *   <li><b>短段落合并到上限</b>：一条两行的 FAQ 单独成片会让向量被噪声主导，
 *       且片数暴涨拖慢检索。</li>
 *   <li><b>超长段落按句子边界切并带重叠</b>——这一层是本次改动的重点，见下。</li>
 * </ol>
 *
 * <h3>为什么要句子边界与重叠</h3>
 * <p>此前超长段落按 {@code start += maxChars} 硬切在<b>字符</b>上，且分片之间零重叠。
 * 现实里「一条制度写成一大段」极常见（本仓库的评估语料第一章就是这样），
 * 于是切点会落在句子中间：前一片以半句结束、后一片以半句开始，两边都读不通。
 * 更麻烦的是它<b>不表现为错误</b>——检索照样返回结果，只是那段话缺了前提或结论，
 * 模型据此给出的答复看起来很像回事。</p>
 *
 * <p>重叠让切点两侧各自保留对方的尾巴，跨切点的问题至少能在一侧被完整召回。
 * 代价是存储与向量数量增加约 {@code overlap/maxChars}，这是检索召回率的合理对价。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class DocumentChunker {

    /** 句末标点：中文优先，英文一并认。切点优先落在这些字符之后。 */
    private static final String SENTENCE_ENDINGS = "。！？；!?;\n";

    /**
     * 回看窗口占分片上限的比例。
     *
     * <p>从目标切点往前找句末标点时，找过头就变成「为了对齐句子把分片切得很短」，
     * 片数暴涨且每片信息量不足。超出这个窗口仍找不到句末，就接受硬切——
     * 那种文本（整段无标点）本来也没有句子边界可言。</p>
     */
    private static final double LOOKBACK_RATIO = 0.3;

    private DocumentChunker() {
    }

    /**
     * 切分文档。
     *
     * @param content      文档全文
     * @param maxChars     单片字符上限
     * @param overlapChars 超长段落被迫切开时，相邻两片的重叠字符数；{@code <= 0} 表示不重叠
     */
    public static List<String> chunk(String content, int maxChars, int overlapChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\\R\\s*\\R")) {
            String normalized = paragraph.strip();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (normalized.length() > maxChars) {
                flush(current, chunks);
                chunks.addAll(splitLongParagraph(normalized, maxChars, overlapChars));
            } else if (current.length() == 0) {
                current.append(normalized);
            } else if (current.length() + 2 + normalized.length() <= maxChars) {
                // 段落之间保留空行，切完的片仍然是一段可读的文本
                current.append("\n\n").append(normalized);
            } else {
                flush(current, chunks);
                current.append(normalized);
            }
        }
        flush(current, chunks);
        // 整篇没有任何可用段落时退回原文，宁可存一份没切的，也不要让这篇文档在知识库里消失
        return chunks.isEmpty() ? List.of(content) : List.copyOf(chunks);
    }

    /**
     * 切开一个超过上限的段落：尽量断在句末，并让相邻片重叠一小段。
     *
     * <p>重叠取的是<b>上一片的结尾</b>而不是下一片的开头往前扩：前者保证
     * 「上一片完整 + 下一片带上文」，后者会让第一片失去尾巴。</p>
     */
    private static List<String> splitLongParagraph(String paragraph, int maxChars, int overlapChars) {
        List<String> pieces = new ArrayList<>();
        int overlap = Math.max(0, Math.min(overlapChars, maxChars / 2));
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(paragraph.length(), start + maxChars);
            if (end < paragraph.length()) {
                end = alignToSentenceEnd(paragraph, start, end, maxChars);
            }
            pieces.add(paragraph.substring(start, end));
            if (end >= paragraph.length()) {
                break;
            }
            // 下一片从「本片结尾往回退 overlap」处起，切点两侧因此各有一份对方的上下文
            int next = end - overlap;
            // 回退不得越过本片起点，否则同一段文本会被无限重复切下去
            start = Math.max(next, start + 1);
        }
        return pieces;
    }

    /**
     * 把切点对齐到最近的句末标点之后。
     *
     * @return 对齐后的切点；回看窗口内没有句末标点时原样返回 {@code end}（接受硬切）
     */
    private static int alignToSentenceEnd(String text, int start, int end, int maxChars) {
        int lowerBound = Math.max(start + 1, end - (int) (maxChars * LOOKBACK_RATIO));
        for (int i = end - 1; i >= lowerBound; i--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(i)) >= 0) {
                // 切在标点之后，标点跟着上一句走
                return i + 1;
            }
        }
        return end;
    }

    private static void flush(StringBuilder current, List<String> chunks) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
