package com.example.customerwork.memory;

import com.example.customerwork.config.CustomerWorkProperties;
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

    @Autowired
    public FactLog(CustomerWorkProperties properties) {
        this(properties.getFactLog().isEnabled(), Path.of(properties.getFactLog().getDirectory()));
    }

    /** 便于测试的构造（可指定临时目录）。 */
    public FactLog(boolean enabled, Path directory) {
        this.enabled = enabled;
        this.directory = directory;
    }

    /** 追加一条事实。空白忽略；写入失败不影响主链路。 */
    public void append(String tenantId, String fact) {
        if (!enabled || fact == null || fact.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(directory);
            ObjectNode node = mapper.createObjectNode();
            node.put("ts", System.currentTimeMillis());
            node.put("tenant", tenantId);
            node.put("fact", fact.trim());
            String line = mapper.writeValueAsString(node) + System.lineSeparator();
            Files.writeString(tenantFile(tenantId), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[FactLog] 写入事实失败（已忽略）: {}", e.getMessage());
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
