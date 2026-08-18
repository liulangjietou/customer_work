package com.richard.fyoung.customeradmin.datascope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DataScope} 单测：多角色取最宽、脏值回落最窄。
 * @author owlzhangfq@gmail.com
 */
class DataScopeTest {

    @Test
    void widest_shouldTakeTheBroaderOne() {
        assertEquals(DataScope.ALL, DataScope.widest(DataScope.ALL, DataScope.SELF));
        assertEquals(DataScope.ALL, DataScope.widest(DataScope.SELF, DataScope.ALL));
        assertEquals(DataScope.TENANT, DataScope.widest(DataScope.TENANT, DataScope.SELF));
        assertEquals(DataScope.SELF, DataScope.widest(DataScope.SELF, DataScope.SELF));
    }

    @Test
    void widest_shouldIgnoreNullSide() {
        assertEquals(DataScope.TENANT, DataScope.widest(null, DataScope.TENANT));
        assertEquals(DataScope.TENANT, DataScope.widest(DataScope.TENANT, null));
    }

    @Test
    void parse_shouldBeCaseInsensitiveAndTrimmed() {
        assertEquals(DataScope.ALL, DataScope.parse("all"));
        assertEquals(DataScope.TENANT, DataScope.parse(" Tenant "));
    }

    /** 脏数据必须回落到最窄：看得少是可修复的，看到全部租户是不可挽回的。 */
    @Test
    void parse_shouldFallbackToSelfOnUnknownValue() {
        assertEquals(DataScope.SELF, DataScope.parse(null));
        assertEquals(DataScope.SELF, DataScope.parse(""));
        assertEquals(DataScope.SELF, DataScope.parse("EVERYTHING"));
    }
}
