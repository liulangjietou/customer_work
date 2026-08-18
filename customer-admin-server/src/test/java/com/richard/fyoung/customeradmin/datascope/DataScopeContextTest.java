package com.richard.fyoung.customeradmin.datascope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DataScopeContext} 单测：只有 SELF 才产生过滤值，以及身份切换后的还原。
 * @author owlzhangfq@gmail.com
 */
class DataScopeContextTest {

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void restrictedUserId_shouldBeNullWhenNoContext() {
        assertNull(DataScopeContext.restrictedUserId());
        assertNull(DataScopeContext.currentScope());
    }

    /** ALL 与 TENANT 都不做用户维度过滤：租户边界由租户拦截器负责，这里只管"谁的"。 */
    @Test
    void restrictedUserId_shouldBeNullForBroaderScopes() {
        DataScopeContext.set(DataScope.ALL, 9L);
        assertNull(DataScopeContext.restrictedUserId());

        DataScopeContext.set(DataScope.TENANT, 9L);
        assertNull(DataScopeContext.restrictedUserId());
    }

    @Test
    void restrictedUserId_shouldReturnUserIdForSelfScope() {
        DataScopeContext.set(DataScope.SELF, 9L);
        assertEquals(9L, DataScopeContext.restrictedUserId());
    }

    @Test
    void callAs_shouldRestorePreviousContext() {
        DataScopeContext.set(DataScope.TENANT, 1L);
        Long inner = DataScopeContext.callAs(42L, DataScopeContext::restrictedUserId);

        assertEquals(42L, inner);
        assertEquals(DataScope.TENANT, DataScopeContext.currentScope());
        assertNull(DataScopeContext.restrictedUserId());
    }

    /** 无上下文时进入 callAs，退出后必须回到"无上下文"，不能残留成 SELF 污染下一个请求。 */
    @Test
    void callAs_shouldClearWhenNoPreviousContext() {
        Long inner = DataScopeContext.callAs(42L, DataScopeContext::restrictedUserId);

        assertEquals(42L, inner);
        assertNull(DataScopeContext.currentScope());
    }
}
