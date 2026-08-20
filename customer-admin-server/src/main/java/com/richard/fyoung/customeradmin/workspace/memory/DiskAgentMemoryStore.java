package com.richard.fyoung.customeradmin.workspace.memory;

import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 长期记忆权威存储的磁盘实现：{@code {disk-root}/{agentCode}/MEMORY.md}。
 * 仅在显式配置 {@code admin.agent-memory.disk-root} 时启用（见 {@link AgentMemoryStoreConfig}），
 * 适用于不便新增数据表、或希望记忆随文件系统备份走的部署场景。
 * @author owlzhangfq@gmail.com
 */
public class DiskAgentMemoryStore implements AgentMemoryStore {

    private final Path root;

    public DiskAgentMemoryStore(Path root) {
        this.root = root;
    }

    @Override
    public Optional<AgentMemorySnapshot> load(String agentCode) {
        Path file = memoryFile(agentCode);
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            LocalDateTime updateTime = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
            return Optional.of(new AgentMemorySnapshot(content, updateTime));
        } catch (IOException e) {
            throw new UncheckedIOException("load agent memory from disk failed: " + file, e);
        }
    }

    @Override
    public void save(String agentCode, String content) {
        Path file = memoryFile(agentCode);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("save agent memory to disk failed: " + file, e);
        }
    }

    @Override
    public void delete(String agentCode) {
        Path file = memoryFile(agentCode);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("delete agent memory on disk failed: " + file, e);
        }
    }

    private Path memoryFile(String agentCode) {
        return root.resolve(agentCode).resolve(AgentFileNames.MEMORY_MD);
    }
}
