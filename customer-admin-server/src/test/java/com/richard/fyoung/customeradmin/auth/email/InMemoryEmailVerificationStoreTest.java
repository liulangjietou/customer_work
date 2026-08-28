package com.richard.fyoung.customeradmin.auth.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 进程内邮箱验证码存储。
 *
 * <p>过期语义由 {@link EmailVerificationCode#remainingSeconds} 承担而不是靠外部 TTL：
 * 失败重写时必须保住原始过期时刻，否则不断试错就能让验证码永不过期。</p>
 */
class InMemoryEmailVerificationStoreTest {

    private static final String EMAIL = "richard@example.com";

    private EmailVerificationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryEmailVerificationStore();
    }

    @Test
    void get_shouldReturnStoredCodeBeforeExpiry() {
        store.save(EMAIL, code("123456", 0, 60_000));

        EmailVerificationCode found = store.get(EMAIL);

        assertNotNull(found);
        assertEquals("123456", found.code());
        assertEquals(0, found.attempts());
    }

    @Test
    void get_shouldReturnNullAfterExpiry() {
        store.save(EMAIL, code("123456", 0, -1_000));

        assertNull(store.get(EMAIL));
    }

    @Test
    void invalidate_shouldRemoveCode() {
        store.save(EMAIL, code("123456", 0, 60_000));

        store.invalidate(EMAIL);

        assertNull(store.get(EMAIL));
    }

    @Test
    void save_shouldOverwritePreviousCodeForSameAddress() {
        store.save(EMAIL, code("111111", 0, 60_000));
        store.save(EMAIL, code("222222", 0, 60_000));

        assertEquals("222222", store.get(EMAIL).code());
    }

    /** 每个邮箱一份，互不干扰。 */
    @Test
    void store_shouldKeepCodesPerAddress() {
        store.save(EMAIL, code("111111", 0, 60_000));
        store.save("other@example.com", code("222222", 0, 60_000));

        assertEquals("111111", store.get(EMAIL).code());
        assertEquals("222222", store.get("other@example.com").code());
    }

    /** 失败计数写回时过期时刻不变——这是"试错不能续期"的机制所在。 */
    @Test
    void withOneMoreFailure_shouldKeepExpiryUnchanged() {
        EmailVerificationCode original = code("123456", 0, 60_000);
        store.save(EMAIL, original);

        store.save(EMAIL, store.get(EMAIL).withOneMoreFailure());

        EmailVerificationCode updated = store.get(EMAIL);
        assertEquals(1, updated.attempts());
        assertEquals(original.expireAtMs(), updated.expireAtMs());
    }

    @Test
    void remainingSeconds_shouldNotGoNegative() {
        assertEquals(0, code("1", 0, -10_000).remainingSeconds(System.currentTimeMillis()));
    }

    private EmailVerificationCode code(String value, int attempts, long offsetMs) {
        return new EmailVerificationCode(value, attempts, System.currentTimeMillis() + offsetMs);
    }
}
