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

    private static final EmailCodePurpose PURPOSE = EmailCodePurpose.REGISTER;
    private static final String EMAIL = "richard@example.com";

    private EmailVerificationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryEmailVerificationStore();
    }

    @Test
    void get_shouldReturnStoredCodeBeforeExpiry() {
        store.save(PURPOSE, EMAIL, code("123456", 0, 60_000));

        EmailVerificationCode found = store.get(PURPOSE, EMAIL);

        assertNotNull(found);
        assertEquals("123456", found.code());
        assertEquals(0, found.attempts());
    }

    @Test
    void get_shouldReturnNullAfterExpiry() {
        store.save(PURPOSE, EMAIL, code("123456", 0, -1_000));

        assertNull(store.get(PURPOSE, EMAIL));
    }

    @Test
    void invalidate_shouldRemoveCode() {
        store.save(PURPOSE, EMAIL, code("123456", 0, 60_000));

        store.invalidate(PURPOSE, EMAIL);

        assertNull(store.get(PURPOSE, EMAIL));
    }

    @Test
    void save_shouldOverwritePreviousCodeForSameAddress() {
        store.save(PURPOSE, EMAIL, code("111111", 0, 60_000));
        store.save(PURPOSE, EMAIL, code("222222", 0, 60_000));

        assertEquals("222222", store.get(PURPOSE, EMAIL).code());
    }

    /** 每个邮箱一份，互不干扰。 */
    @Test
    void store_shouldKeepCodesPerAddress() {
        store.save(PURPOSE, EMAIL, code("111111", 0, 60_000));
        store.save(PURPOSE, "other@example.com", code("222222", 0, 60_000));

        assertEquals("111111", store.get(PURPOSE, EMAIL).code());
        assertEquals("222222", store.get(PURPOSE, "other@example.com").code());
    }

    /** 失败计数写回时过期时刻不变——这是"试错不能续期"的机制所在。 */
    @Test
    void withOneMoreFailure_shouldKeepExpiryUnchanged() {
        EmailVerificationCode original = code("123456", 0, 60_000);
        store.save(PURPOSE, EMAIL, original);

        store.save(PURPOSE, EMAIL, store.get(PURPOSE, EMAIL).withOneMoreFailure());

        EmailVerificationCode updated = store.get(PURPOSE, EMAIL);
        assertEquals(1, updated.attempts());
        assertEquals(original.expireAtMs(), updated.expireAtMs());
    }

    @Test
    void remainingSeconds_shouldNotGoNegative() {
        assertEquals(0, code("1", 0, -10_000).remainingSeconds(System.currentTimeMillis()));
    }

    /**
     * 同一个邮箱在两种用途下各存各的。
     *
     * <p>共用一个键空间意味着两种码可以互相顶替——而注册码是任何人对着一个未注册邮箱
     * 都能索取的，拿它去重置同一邮箱下账号的密码就成立了。</p>
     */
    @Test
    void save_shouldKeepPurposesInSeparateSpaces() {
        store.save(EmailCodePurpose.REGISTER, EMAIL, code("111111", 0, 60_000));
        store.save(EmailCodePurpose.PASSWORD_RESET, EMAIL, code("222222", 0, 60_000));

        assertEquals("111111", store.get(EmailCodePurpose.REGISTER, EMAIL).code());
        assertEquals("222222", store.get(EmailCodePurpose.PASSWORD_RESET, EMAIL).code());
    }

    @Test
    void invalidate_shouldOnlyAffectTheGivenPurpose() {
        store.save(EmailCodePurpose.REGISTER, EMAIL, code("111111", 0, 60_000));
        store.save(EmailCodePurpose.PASSWORD_RESET, EMAIL, code("222222", 0, 60_000));

        store.invalidate(EmailCodePurpose.REGISTER, EMAIL);

        assertNull(store.get(EmailCodePurpose.REGISTER, EMAIL));
        assertNotNull(store.get(EmailCodePurpose.PASSWORD_RESET, EMAIL), "作废一种用途不该殃及另一种");
    }

    private EmailVerificationCode code(String value, int attempts, long offsetMs) {
        return new EmailVerificationCode(value, attempts, System.currentTimeMillis() + offsetMs);
    }
}
