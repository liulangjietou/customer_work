package com.richard.fyoung.customerwork.safety.subjectquota;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主体值对象单测：API Key 只留指纹、计数键按类型隔离、空值兜底。
 * @author owlzhangfq@gmail.com
 */
class QuotaSubjectTest {

    @Test
    void apiKey_shouldNotKeepPlainText() {
        QuotaSubject subject = QuotaSubject.apiKey("sk-super-secret-key");
        assertFalse(subject.id().contains("secret"), "明文 Key 会进 Redis 键、命中表与日志，绝不能保留");
        assertEquals(16, subject.id().length(), "指纹取 SHA-256 前 16 位十六进制");
    }

    @Test
    void apiKey_shouldBeStable_forSameKey() {
        assertEquals(QuotaSubject.apiKey("sk-a").id(), QuotaSubject.apiKey("sk-a").id(),
            "同一把 Key 必须落到同一份额度上");
        assertNotEquals(QuotaSubject.apiKey("sk-a").id(), QuotaSubject.apiKey("sk-b").id());
    }

    @Test
    void counterKey_shouldSeparateTypes() {
        // 同名标识但类型不同（如用户 ID 恰好等于某个 IP 字面量）不得共享额度
        assertNotEquals(QuotaSubject.user("1.2.3.4").counterKey(), QuotaSubject.ip("1.2.3.4").counterKey());
    }

    @Test
    void blankIdentifier_shouldFallBackToUnknown() {
        assertEquals(QuotaSubject.UNKNOWN_ID, QuotaSubject.user("  ").id(),
            "解析不出身份也要有一份额度可算，否则等于放行");
        assertTrue(QuotaSubject.ip(null).counterKey().endsWith(QuotaSubject.UNKNOWN_ID));
    }

    @Test
    void adminUser_shouldNotShareQuotaWithEndUser() {
        // sys_user 与 cw_user 是两套 ID 空间，同一个 ID 值指的是不同的人；
        // 共用一个类型就会让一个后台管理员和一个终端用户莫名其妙共享额度
        assertNotEquals(QuotaSubject.adminUser("1").counterKey(), QuotaSubject.user("1").counterKey());
        assertEquals(QuotaSubjectType.ADMIN_USER, QuotaSubject.adminUser("1").type());
    }
}
