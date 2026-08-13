package com.richard.fyoung.customerwork.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 事实日志的落盘实现（{@code customer-work.fact-log.store-mode=file} 时装配）：按分区写 JSONL。
 *
 * <p>采用 append-only 写入（{@link StandardOpenOption#APPEND}），保证历史事实不被覆盖；
 * 单文件超过上限时轮转为 {@code .1 / .2 / ...} 归档。</p>
 *
 * <p><b>局限</b>：文件在单机本地，多副本部署时各副本只看得到自己写的那部分，且随容器销毁而丢失。
 * 生产请用默认的 {@link MybatisFactLog}；本实现留给不便建表、或希望事实随文件系统备份走的部署场景。</p>
 * @author owlzhangfq@gmail.com
 */
public class FileFactLog implements FactLog {

    private static final Logger log = LoggerFactory.getLogger(FileFactLog.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;
    private final Path directory;
    private final int maxFileBytes;
    private final int maxArchivedFiles;

    /** 便于测试的构造（可指定临时目录，不轮转）。 */
    public FileFactLog(boolean enabled, Path directory) {
        this(enabled, directory, 0, 0);
    }

    /** 完整构造（含轮转配置）。 */
    public FileFactLog(boolean enabled, Path directory, int maxFileMb, int maxArchivedFiles) {
        this.enabled = enabled;
        this.directory = directory;
        this.maxFileBytes = maxFileMb > 0 ? maxFileMb * 1024 * 1024 : 0;
        this.maxArchivedFiles = maxArchivedFiles;
    }

    @Override
    public void append(String scopeId, String fact) {
        if (!enabled || fact == null || fact.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Path file = scopeFile(scopeId);
            // 文件轮转：超过大小上限时归档
            if (maxFileBytes > 0 && Files.exists(file) && Files.size(file) >= maxFileBytes) {
                rotateFile(file);
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("ts", System.currentTimeMillis());
            node.put("tenant", scopeId);
            node.put("fact", fact.trim());
            String line = mapper.writeValueAsString(node) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("append fact to file failed, code={}, scopeId={}", "FACT-LOG-APPEND-FAIL", scopeId, e);
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
            log.info("fact log rotated {} -> {}", file, archive);
        } catch (IOException e) {
            log.error("rotate fact log file failed (继续追加写入), code={}, file={}",
                "FACT-LOG-ROTATE-FAIL", file, e);
        }
    }

    @Override
    public List<String> read(String scopeId) {
        Path file = scopeFile(scopeId);
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
            log.error("read facts from file failed, code={}, scopeId={}", "FACT-LOG-READ-FAIL", scopeId, e);
        }
        return facts;
    }

    @Override
    public List<FactRecord> readRecords(String scopeId) {
        Path file = scopeFile(scopeId);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<FactRecord> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                var node = mapper.readTree(line);
                records.add(new FactRecord(node.path("ts").asLong(), scopeId, node.path("fact").asText()));
            }
        } catch (IOException e) {
            log.error("read fact records from file failed, code={}, scopeId={}",
                "FACT-LOG-READ-RECORDS-FAIL", scopeId, e);
        }
        return records;
    }

    private Path scopeFile(String scopeId) {
        String safe = scopeId == null ? "default" : scopeId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return directory.resolve(safe + ".jsonl");
    }
}
