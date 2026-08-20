package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Harness 分层记忆同步：权威存储（{@link HarnessMemoryStore}）与框架工作副本
 * （{@code {workspace}/MEMORY.md}）之间的双向同步。
 *
 * <ul>
 *   <li><b>水合</b>（{@link #hydrate}）：构建 HarnessAgent 时把权威副本写到 workspace，保证换机 / 重启 /
 *       清理 workspace 后框架仍读得到历史记忆；权威侧为空而 workspace 有存量文件时反向入库（存量迁移）。</li>
 *   <li><b>回写</b>（{@link #persistIfChanged}）：对话轮次结束后把 workspace 文件的变更存回权威存储；
 *       内容未变化时跳过写入。</li>
 * </ul>
 *
 * <p>两个方法都不抛异常（同步失败不该打断对话主链路，也不该阻断实例构建），失败只记 error；
 * 这是本链路的唯一异常兜底点，{@link HarnessMemoryStore} 实现内部不再兜底。
 * 与 admin-server 的 {@code AgentMemorySyncService} 是同一套手法，键空间不同故各自一份。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class HarnessMemorySyncService {

    private static final Logger log = LoggerFactory.getLogger(HarnessMemorySyncService.class);

    /** 框架分层记忆的工作副本文件名（workspace 根下，路径约定来自 Harness WorkspaceManager）。 */

    private final HarnessMemoryStore memoryStore;

    public HarnessMemorySyncService(HarnessMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /** 水合：权威存储 → workspace/MEMORY.md；权威侧为空且 workspace 有存量文件时反向入库。 */
    public void hydrate(Path workspace) {
        String scopeId = scopeOf(workspace);
        try {
            Path memoryFile = workspace.resolve(AgentFileNames.MEMORY_MD);
            Optional<String> stored = memoryStore.load(scopeId);
            if (stored.isPresent()) {
                Files.createDirectories(workspace);
                Files.writeString(memoryFile, stored.get(), StandardCharsets.UTF_8);
                log.info("harness memory hydrated to workspace: scopeId={}", scopeId);
                return;
            }
            if (Files.exists(memoryFile)) {
                // 存量迁移：老版本记忆只落过 workspace 磁盘，第一次构建时收编进权威存储
                memoryStore.save(scopeId, Files.readString(memoryFile, StandardCharsets.UTF_8));
                log.info("legacy workspace memory imported into store: scopeId={}", scopeId);
            }
        } catch (Exception e) {
            log.error("hydrate harness memory failed, code={}, scopeId={}",
                "HARNESS-MEMORY-HYDRATE-FAIL", scopeId, e);
        }
    }

    /** 回写：workspace/MEMORY.md → 权威存储（对话轮次结束后调用）；文件不存在或内容未变化时跳过。 */
    public void persistIfChanged(Path workspace) {
        String scopeId = scopeOf(workspace);
        try {
            Path memoryFile = workspace.resolve(AgentFileNames.MEMORY_MD);
            if (!Files.exists(memoryFile)) {
                return;
            }
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            if (content.equals(memoryStore.load(scopeId).orElse(null))) {
                return;
            }
            memoryStore.save(scopeId, content);
        } catch (Exception e) {
            log.error("persist harness memory failed, code={}, scopeId={}",
                "HARNESS-MEMORY-PERSIST-FAIL", scopeId, e);
        }
    }

    /** 记忆归属键：workspace 的绝对规范化路径，保证相对路径与绝对路径写法指向同一行。 */
    private static String scopeOf(Path workspace) {
        return workspace.toAbsolutePath().normalize().toString();
    }
}
