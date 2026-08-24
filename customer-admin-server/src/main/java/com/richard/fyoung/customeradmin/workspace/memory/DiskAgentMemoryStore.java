package com.richard.fyoung.customeradmin.workspace.memory;

import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
            return withFileLock(agentCode, () -> {
                if (!Files.exists(file)) {
                    return Optional.empty();
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                LocalDateTime updateTime = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
                return Optional.of(new AgentMemorySnapshot(content, updateTime, readVersion(agentCode)));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("load agent memory from disk failed: " + file, e);
        }
    }

    @Override
    public boolean compareAndSet(String agentCode, String content, long expectedVersion) {
        Path file = memoryFile(agentCode);
        try {
            Files.createDirectories(file.getParent());
            return withFileLock(agentCode, () -> {
                long currentVersion = Files.exists(file) ? readVersion(agentCode) : 0L;
                if (currentVersion != expectedVersion) {
                    return false;
                }
                Path temporary = Files.createTempFile(file.getParent(), "memory-", ".tmp");
                try {
                    Files.writeString(temporary, content, StandardCharsets.UTF_8);
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(versionFile(agentCode), Long.toString(currentVersion + 1L),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return true;
            });
        } catch (IOException e) {
            throw new UncheckedIOException("save agent memory to disk failed: " + file, e);
        }
    }

    @Override
    public void delete(String agentCode) {
        Path file = memoryFile(agentCode);
        try {
            if (!Files.exists(file) && !Files.exists(versionFile(agentCode))) {
                return;
            }
            withFileLock(agentCode, () -> {
                Files.deleteIfExists(file);
                Files.deleteIfExists(versionFile(agentCode));
                return null;
            });
        } catch (IOException e) {
            throw new UncheckedIOException("delete agent memory on disk failed: " + file, e);
        }
    }

    private Path memoryFile(String agentCode) {
        return root.resolve(agentCode).resolve(AgentFileNames.MEMORY_MD);
    }

    private Path versionFile(String agentCode) {
        return root.resolve(agentCode).resolve(AgentFileNames.MEMORY_MD + ".version");
    }

    private Path lockFile(String agentCode) {
        return root.resolve(agentCode).resolve(AgentFileNames.MEMORY_MD + ".lock");
    }

    private long readVersion(String agentCode) throws IOException {
        Path version = versionFile(agentCode);
        return Files.exists(version)
            ? Long.parseLong(Files.readString(version, StandardCharsets.UTF_8).trim()) : 1L;
    }

    private <T> T withFileLock(String agentCode, IoSupplier<T> action) throws IOException {
        Path lock = lockFile(agentCode);
        Files.createDirectories(lock.getParent());
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return action.get();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
