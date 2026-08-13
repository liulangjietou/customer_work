package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * VibeCoding 会话工作区的权威存储：整棵目录树打成 tar.gz 存进对象存储，一个会话一个对象。
 *
 * <p><b>为什么需要它</b>：会话产出物（生成的代码）此前只存在于磁盘一份，而工作区已移出项目目录、
 * 落在系统临时目录下（见 {@code RuntimeWorkDir}），会被 OS 定期清理；容器化部署时更是容器一销毁就没了。
 * 产出物是用户在会话里生成的代码，<b>不是可重建的派生物</b>，必须有权威副本。</p>
 *
 * <p><b>为什么整棵树打包而不是逐文件同步</b>：{@code .git} 必须一起同步——{@code GitWorkspaceService#ensureRepo}
 * 见到没有 {@code .git} 就用当前内容建新基线，只同步业务文件的话，恢复后"本轮变更"diff 会变空、
 * 一键回滚会回滚到"已修改"状态，两个功能静默失效。而 {@code .git} 动辄上百个小对象，逐个传既慢又难保证
 * 一致性；打成单个归档则天然原子、且 tar 能保住可执行位（脚本与 git 钩子需要）。</p>
 *
 * <p><b>已知限制</b>：恢复以"本地目录为空"为触发条件，多副本部署时 A 副本持有本地旧副本、B 副本更新了
 * 对象存储，A 不会重新拉取。当前 admin 为单实例部署，如需多副本请配会话粘滞或引入版本号比对。</p>
 * @author owlzhangfq@gmail.com
 */
public class SessionWorkspaceStorage {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaceStorage.class);

    private final AttachmentFileStorage fileStorage;
    private final String keyPrefix;
    private final long maxArchiveBytes;

    /**
     * @param fileStorage     对象存储（VibeCoding 专用实例，独立 bucket）
     * @param keyPrefix       对象 key 前缀
     * @param maxArchiveBytes 归档大小上限：归档在内存里构建，不封顶会被一个巨大的工作区打爆
     */
    public SessionWorkspaceStorage(AttachmentFileStorage fileStorage, String keyPrefix, long maxArchiveBytes) {
        this.fileStorage = fileStorage;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? ""
            : (keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/");
        this.maxArchiveBytes = maxArchiveBytes;
    }

    /**
     * 恢复：对象存储 → 本地工作区。<b>仅当本地工作区不存在或为空时</b>执行，避免覆盖本进程刚写出的内容。
     *
     * <p>不抛异常——恢复失败退化为"从空工作区开始"，不该让整个会话打不开。</p>
     */
    public void hydrate(String agentCode, String sessionId, Path workspace) {
        try {
            if (!isEmptyDir(workspace)) {
                return;
            }
            byte[] archive;
            try {
                archive = fileStorage.read(objectKey(agentCode, sessionId));
            } catch (Exception e) {
                // 对象不存在是常态（新会话），不记 error 免得刷屏
                return;
            }
            extractTo(archive, workspace);
            log.info("vibecoding workspace hydrated, agentCode={}, sessionId={}, bytes={}",
                agentCode, sessionId, archive.length);
        } catch (Exception e) {
            log.error("hydrate vibecoding workspace failed, code={}, agentCode={}, sessionId={}",
                "VIBECODING-WORKSPACE-HYDRATE-FAIL", agentCode, sessionId, e);
        }
    }

    /**
     * 保存：本地工作区 → 对象存储（整树覆盖）。
     *
     * <p>不抛异常——保存失败不该打断对话主链路，但会记 error（这是产出物唯一的持久化路径，失败必须可见）。</p>
     */
    public void persist(String agentCode, String sessionId, Path workspace) {
        try {
            if (!Files.isDirectory(workspace)) {
                return;
            }
            byte[] archive = archive(workspace);
            if (archive.length > maxArchiveBytes) {
                log.error("vibecoding workspace too large to persist, code={}, agentCode={}, sessionId={}, bytes={}, limit={}",
                    "VIBECODING-WORKSPACE-TOO-LARGE", agentCode, sessionId, archive.length, maxArchiveBytes);
                return;
            }
            fileStorage.storeAt(objectKey(agentCode, sessionId), archive);
            log.info("vibecoding workspace persisted, agentCode={}, sessionId={}, bytes={}",
                agentCode, sessionId, archive.length);
        } catch (Exception e) {
            log.error("persist vibecoding workspace failed, code={}, agentCode={}, sessionId={}",
                "VIBECODING-WORKSPACE-PERSIST-FAIL", agentCode, sessionId, e);
        }
    }

    /** 对象 key：{@code {prefix}{agentCode}/{sessionId}.tar.gz}，一个会话恒定一个对象。 */
    String objectKey(String agentCode, String sessionId) {
        return keyPrefix + agentCode + "/" + sessionId + ".tar.gz";
    }

    /** 目录不存在或没有任何条目时视为空。 */
    private static boolean isEmptyDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /** 把整棵目录树打成 tar.gz（保留相对路径与可执行位；符号链接按其指向的普通文件写入）。 */
    byte[] archive(Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            // 路径可能超过 tar 头部 100 字节的历史限制（.git 下的深层对象目录很容易超），用 POSIX 扩展头
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = root.relativize(file).toString();
                    TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), name);
                    entry.setSize(attrs.size());
                    tar.putArchiveEntry(entry);
                    Files.copy(file, tar);
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // 单个文件读不到（临时文件被删/权限）不该让整次归档失败
                    log.error("skip unreadable file while archiving workspace, code={}, file={}",
                        "VIBECODING-WORKSPACE-ARCHIVE-SKIP", file, e);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return out.toByteArray();
    }

    /**
     * 解包到目标目录（先清空，保证与归档完全一致）。
     *
     * <p>逐条目校验解出的路径必须仍落在目标目录内——归档内容虽由本服务自己产生，但对象存储里的
     * 数据可能被篡改，{@code ../} 条目会写到工作区之外（tar slip）。</p>
     */
    void extractTo(byte[] archive, Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        deleteRecursively(root);
        Files.createDirectories(root);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(new ByteArrayInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    log.error("skip tar entry escaping workspace, code={}, entry={}",
                        "VIBECODING-WORKSPACE-TAR-SLIP", entry.getName());
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(tar, target);
            }
        }
    }

    /** 递归删除目录（不存在则跳过）。 */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
