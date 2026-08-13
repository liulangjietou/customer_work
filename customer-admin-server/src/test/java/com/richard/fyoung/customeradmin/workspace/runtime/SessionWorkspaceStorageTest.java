package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionWorkspaceStorage} 单测：整树往返、{@code .git} 一并保住、空目录才恢复、
 * 超限跳过、tar slip 拦截。
 *
 * <p>{@code .git} 那条是本类的重点——{@code GitWorkspaceService#ensureRepo} 见不到 {@code .git}
 * 就会用当前内容建新基线，只同步业务文件的话"本轮变更"diff 会变空、一键回滚会回滚到已修改状态。</p>
 * @author owlzhangfq@gmail.com
 */
class SessionWorkspaceStorageTest {

    private static final long BIG_ENOUGH = 100L * 1024 * 1024;

    @Test
    void persistThenHydrate_shouldRoundTripWholeTree(@TempDir Path tmp) throws Exception {
        InMemoryObjectStore store = new InMemoryObjectStore();
        SessionWorkspaceStorage storage = new SessionWorkspaceStorage(store, "workspaces/", BIG_ENOUGH);

        Path src = Files.createDirectories(tmp.resolve("src-ws"));
        Files.writeString(src.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.createDirectories(src.resolve("src/main/java/com/demo"));
        Files.writeString(src.resolve("src/main/java/com/demo/App.java"), "class App {}", StandardCharsets.UTF_8);

        storage.persist("coder", "sess-1", src);

        Path restored = tmp.resolve("restored-ws");
        storage.hydrate("coder", "sess-1", restored);

        assertEquals("<project/>", Files.readString(restored.resolve("pom.xml"), StandardCharsets.UTF_8));
        assertEquals("class App {}",
            Files.readString(restored.resolve("src/main/java/com/demo/App.java"), StandardCharsets.UTF_8));
    }

    @Test
    void persistThenHydrate_shouldPreserveGitDir(@TempDir Path tmp) throws Exception {
        InMemoryObjectStore store = new InMemoryObjectStore();
        SessionWorkspaceStorage storage = new SessionWorkspaceStorage(store, "workspaces/", BIG_ENOUGH);

        Path src = Files.createDirectories(tmp.resolve("src-ws"));
        Files.writeString(src.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        // 模拟 git 仓库：objects 目录路径很深，同时验证长路径不被 tar 头部长度限制截断
        Path deep = src.resolve(".git/objects/ab/cdef0123456789abcdef0123456789abcdef01");
        Files.createDirectories(deep.getParent());
        Files.write(deep, new byte[] {1, 2, 3});
        Files.writeString(src.resolve(".git/HEAD"), "ref: refs/heads/master\n", StandardCharsets.UTF_8);

        storage.persist("coder", "sess-git", src);
        Path restored = tmp.resolve("restored-ws");
        storage.hydrate("coder", "sess-git", restored);

        assertTrue(Files.isDirectory(restored.resolve(".git")), ".git 必须一并恢复，否则回滚与变更 diff 失效");
        assertEquals("ref: refs/heads/master\n",
            Files.readString(restored.resolve(".git/HEAD"), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[] {1, 2, 3},
            Files.readAllBytes(restored.resolve(".git/objects/ab/cdef0123456789abcdef0123456789abcdef01")));
    }

    @Test
    void hydrate_shouldSkip_whenLocalWorkspaceNotEmpty(@TempDir Path tmp) throws Exception {
        InMemoryObjectStore store = new InMemoryObjectStore();
        SessionWorkspaceStorage storage = new SessionWorkspaceStorage(store, "workspaces/", BIG_ENOUGH);

        Path src = Files.createDirectories(tmp.resolve("src-ws"));
        Files.writeString(src.resolve("a.txt"), "远端旧内容", StandardCharsets.UTF_8);
        storage.persist("coder", "sess-2", src);

        // 本地已有内容：不能被远端覆盖（否则本进程刚写出的产出物会被旧副本顶掉）
        Path local = Files.createDirectories(tmp.resolve("local-ws"));
        Files.writeString(local.resolve("a.txt"), "本地新内容", StandardCharsets.UTF_8);
        storage.hydrate("coder", "sess-2", local);

        assertEquals("本地新内容", Files.readString(local.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void hydrate_shouldDoNothing_whenObjectAbsent(@TempDir Path tmp) {
        SessionWorkspaceStorage storage =
            new SessionWorkspaceStorage(new InMemoryObjectStore(), "workspaces/", BIG_ENOUGH);

        Path fresh = tmp.resolve("brand-new");
        storage.hydrate("coder", "never-saved", fresh);

        // 新会话没有权威副本是常态，不该抛异常、也不该造出内容
        assertFalse(Files.exists(fresh.resolve("anything")));
    }

    @Test
    void persist_shouldSkip_whenArchiveExceedsLimit(@TempDir Path tmp) throws Exception {
        InMemoryObjectStore store = new InMemoryObjectStore();
        SessionWorkspaceStorage storage = new SessionWorkspaceStorage(store, "workspaces/", 64);

        Path src = Files.createDirectories(tmp.resolve("src-ws"));
        // 随机字节，避免被 gzip 压到限额以下
        byte[] bulky = new byte[8192];
        new java.util.Random(42).nextBytes(bulky);
        Files.write(src.resolve("big.bin"), bulky);

        storage.persist("coder", "sess-big", src);

        assertTrue(store.objects.isEmpty(), "超过上限时应跳过保存，不能把内存打爆");
    }

    @Test
    void hydrate_shouldRejectTarEntryEscapingWorkspace(@TempDir Path tmp) throws Exception {
        InMemoryObjectStore store = new InMemoryObjectStore();
        SessionWorkspaceStorage storage = new SessionWorkspaceStorage(store, "workspaces/", BIG_ENOUGH);
        // 构造被篡改的归档：一条 ../escaped.txt 试图写到工作区之外
        store.objects.put(storage.objectKey("coder", "evil"), maliciousArchive());

        Path ws = tmp.resolve("ws");
        storage.hydrate("coder", "evil", ws);

        assertFalse(Files.exists(tmp.resolve("escaped.txt")), "越界条目不该被写出到工作区之外");
        assertTrue(Files.exists(ws.resolve("ok.txt")), "同归档内的合法条目应正常解出");
    }

    /** 含一条越界条目 + 一条正常条目的 tar.gz。 */
    private static byte[] maliciousArchive() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            writeEntry(tar, "../escaped.txt", "secret");
            writeEntry(tar, "ok.txt", "fine");
        }
        return out.toByteArray();
    }

    private static void writeEntry(TarArchiveOutputStream tar, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    /** 进程内对象存储替身：只用到 storeAt / read。 */
    private static class InMemoryObjectStore implements AttachmentFileStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public String store(byte[] data, String id, String ext) {
            String key = id + (ext == null || ext.isEmpty() ? "" : "." + ext);
            objects.put(key, data);
            return key;
        }

        @Override
        public void storeAt(String storagePath, byte[] data) {
            objects.put(storagePath, data);
        }

        @Override
        public byte[] read(String storagePath) throws IOException {
            byte[] data = objects.get(storagePath);
            if (data == null) {
                throw new IOException("object not found: " + storagePath);
            }
            return data;
        }

        @Override
        public void delete(String storagePath) {
            objects.remove(storagePath);
        }
    }
}
