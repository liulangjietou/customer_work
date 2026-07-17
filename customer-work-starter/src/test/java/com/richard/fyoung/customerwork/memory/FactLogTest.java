package com.richard.fyoung.customerwork.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事实日志单测（三层记忆第三层）：只追加持久化、跨租户隔离、可禁用、文件轮转。
 * @author owlzhangfq@gmail.com
 */
class FactLogTest {

    @Test
    void appendThenRead_shouldPersistInOrder(@TempDir Path dir) {
        FactLog log = new FactLog(true, dir);
        log.append("tenantA", "用户偏好顺丰快递");
        log.append("tenantA", "用户常用收货地址杭州");

        List<String> facts = log.read("tenantA");
        assertEquals(2, facts.size());
        assertEquals("用户偏好顺丰快递", facts.get(0));
        assertEquals("用户常用收货地址杭州", facts.get(1));
    }

    @Test
    void read_shouldIsolateBetweenTenants(@TempDir Path dir) {
        FactLog log = new FactLog(true, dir);
        log.append("tenantA", "A 的事实");

        assertTrue(log.read("tenantB").isEmpty(), "租户隔离：B 不应看到 A 的事实日志");
    }

    @Test
    void append_shouldBeNoOp_whenDisabled(@TempDir Path dir) {
        FactLog log = new FactLog(false, dir);
        log.append("tenantA", "不应写入");
        assertTrue(log.read("tenantA").isEmpty(), "禁用时不应写入");
    }

    @Test
    void append_shouldIgnoreBlank(@TempDir Path dir) {
        FactLog log = new FactLog(true, dir);
        log.append("t", "  ");
        log.append("t", null);
        assertTrue(log.read("t").isEmpty());
    }

    // ======== 文件轮转测试 ========

    @Test
    void shouldRotateFile_whenExceedingMaxSize(@TempDir Path dir) throws Exception {
        // maxFileMb=1 即 1MB，写入超过后应轮转
        FactLog log = new FactLog(true, dir, 1, 3);
        Path dataFile = dir.resolve("tenantA.jsonl");

        // 写入大量事实直到超过 1MB（每条约 120 字节，需 10000+ 条）
        for (int i = 0; i < 12000; i++) {
            log.append("tenantA", "这是一条测试事实用于触发文件轮转 " + i);
        }

        // 原文件应存在（轮转后新建的）
        assertTrue(Files.exists(dataFile), "轮转后当前文件应存在");
        // 归档文件 .1 应存在
        Path archive1 = dir.resolve("tenantA.jsonl.1");
        assertTrue(Files.exists(archive1), "归档文件 .1 应存在");
    }

    @Test
    void shouldNotRotate_whenMaxFileMbIsZero(@TempDir Path dir) {
        FactLog log = new FactLog(true, dir, 0, 3);

        // maxFileMb=0 = 禁用轮转，写入大量事实不应产生归档文件
        for (int i = 0; i < 1000; i++) {
            log.append("tenantA", "测试事实 " + i);
        }

        Path archive1 = dir.resolve("tenantA.jsonl.1");
        assertTrue(!Files.exists(archive1), "禁用轮转时不应产生归档文件");
    }

    @Test
    void shouldDeleteOldestArchive_whenExceedingMaxArchivedFiles(@TempDir Path dir) throws Exception {
        // maxFileMb=1, maxArchivedFiles=2 → 最多保留 .1 和 .2
        FactLog log = new FactLog(true, dir, 1, 2);

        // 多次填充触发多轮轮转（每轮 12000 条约 1.4MB，确保触发轮转）
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 12000; i++) {
                log.append("tenantA", "轮转测试事实 round=" + round + " i=" + i);
            }
        }

        // .1 和 .2 应存在，.3 不应存在（超出上限被删除）
        Path archive1 = dir.resolve("tenantA.jsonl.1");
        Path archive2 = dir.resolve("tenantA.jsonl.2");
        Path archive3 = dir.resolve("tenantA.jsonl.3");
        assertTrue(Files.exists(archive1), "归档 .1 应存在");
        assertTrue(Files.exists(archive2), "归档 .2 应存在");
        assertTrue(!Files.exists(archive3), "归档 .3 不应存在（超出上限）");
    }
}
