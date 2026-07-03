package com.richard.fyoung.customerwork.memory;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 事实日志（对应实战「三层记忆体系」的第三层：只追加事实日志）。
 *
 * <p>三层记忆体系：</p>
 * <ol>
 *   <li><b>L1 上下文内对话</b>：会话级短期记忆（{@code InMemoryMemory} / {@code AutoContextMemory}）；</li>
 *   <li><b>L2 长期记忆</b>：可语义召回的跨会话记忆（{@code LongTermMemory}）；</li>
 *   <li><b>L3 事实日志</b>：本类——只追加、不可变、可审计的事实流水，按租户落盘为 JSONL，
 *       用于合规审计与"数据飞轮"回放，永不被压缩或改写。</li>
 * </ol>
 *
 * <p>采用 append-only 写入（{@link StandardOpenOption#APPEND}），保证历史事实不被覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class FactLog {

    private static final Logger log = LoggerFactory.getLogger(FactLog.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;
    private final Path directory;
    private final int maxFileBytes;
    private final int maxArchivedFiles;

    @Autowired
    public FactLog(CustomerWorkProperties properties) {
        this(properties.getFactLog().isEnabled(),
             Path.of(properties.getFactLog().getDirectory()),
             properties.getFactLog().getMaxFileMb(),
             properties.getFactLog().getMaxArchivedFiles());
    }

    /** 便于测试的构造（可指定临时目录）。 */
    public FactLog(boolean enabled, Path directory) {
        this(enabled, directory, 0, 0);
    }

    /** 完整构造（含轮转配置）。 */
    public FactLog(boolean enabled, Path directory, int maxFileMb, int maxArchivedFiles) {
        this.enabled = enabled;
        this.directory = directory;
        this.maxFileBytes = maxFileMb > 0 ? maxFileMb * 1024 * 1024 : 0;
        this.maxArchivedFiles = maxArchivedFiles;
    }

    /** 追加一条事实。空白忽略；写入失败不影响主链路。 */
    public void append(String tenantId, String fact) {
        if (!enabled || fact == null || fact.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Path file = tenantFile(tenantId);
            // 文件轮转：超过大小上限时归档
            if (maxFileBytes > 0 && Files.exists(file) && Files.size(file) >= maxFileBytes) {
                rotateFile(file);
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("ts", System.currentTimeMillis());
            node.put("tenant", tenantId);
            node.put("fact", fact.trim());
            String line = mapper.writeValueAsString(node) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[FactLog] 写入事实失败（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * 文件轮转：将当前文件重命名为 .1，.1 -> .2，... 删除超出 maxArchivedFiles 的旧文件。
     */
    private void rotateFile(Path file) {
        try {
            // 从后往前滚动归档文件
            int max = maxArchivedFiles > 0 ? maxArchivedFiles : Integer.MAX_VALUE;
            for (int i = max; i >= 1; i--) {
                Path older = file.resolveSibling(file.getFileName() + "." + i);
                if (!Files.exists(older)) {
                    continue;
                }
                if (i >= max) {
                    Files.deleteIfExists(older);  // 超出上限的最旧文件删除
                } else {
                    Path newer = file.resolveSibling(file.getFileName() + "." + (i + 1));
                    Files.move(older, newer);
                }
            }
            // 当前文件 -> .1
            Path archive = file.resolveSibling(file.getFileName() + ".1");
            Files.move(file, archive);
            log.info("[FactLog] rotated {} -> {}", file, archive);
        } catch (IOException e) {
            log.warn("[FactLog] 文件轮转失败（继续追加写入）: {}", e.getMessage());
        }
    }

    /** 读取某租户的全部事实（按写入顺序）。 */
    public List<String> read(String tenantId) {
        Path file = tenantFile(tenantId);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> facts = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                facts.add(mapper.readTree(line).path("fact").asText());
            }
        } catch (IOException e) {
            log.warn("[FactLog] 读取事实失败（已忽略）: {}", e.getMessage());
        }
        return facts;
    }

    private Path tenantFile(String tenantId) {
        String safe = tenantId == null ? "default" : tenantId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return directory.resolve(safe + ".jsonl");
    }
}
