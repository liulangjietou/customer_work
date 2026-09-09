package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.retrieval;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 改动前切分算法的<b>历史快照</b>，只用于检索质量对比，不参与生产。
 *
 * <p>它是从 {@code KnowledgeDocumentIndexer#chunk} 原样搬来的一份副本。
 * 刻意不复用生产代码：基线的意义就在于「拿今天的实现和当初那一版比」，
 * 让它跟着生产代码一起演进的话，两边会一起变好或一起变差，差值恒为零。
 * 因此这份副本<b>不要更新</b>——它记录的是 2026-09-09 之前的行为。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class LegacyChunker {

    private LegacyChunker() {
    }

    static List<String> chunk(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\\R\\s*\\R")) {
            String normalized = paragraph.strip();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (normalized.length() > maxChars) {
                flush(current, chunks);
                // 病根在这一行：按字符硬切，且分片之间零重叠
                for (int start = 0; start < normalized.length(); start += maxChars) {
                    chunks.add(normalized.substring(start, Math.min(normalized.length(), start + maxChars)));
                }
            } else if (current.length() == 0) {
                current.append(normalized);
            } else if (current.length() + 2 + normalized.length() <= maxChars) {
                current.append("\n\n").append(normalized);
            } else {
                flush(current, chunks);
                current.append(normalized);
            }
        }
        flush(current, chunks);
        return chunks.isEmpty() ? List.of(content) : List.copyOf(chunks);
    }

    private static void flush(StringBuilder current, List<String> chunks) {
        if (current.length() > 0) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }
}
