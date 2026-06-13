package com.example.customerwork.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事实日志单测（三层记忆第三层）：只追加持久化、跨租户隔离、可禁用。
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
}
