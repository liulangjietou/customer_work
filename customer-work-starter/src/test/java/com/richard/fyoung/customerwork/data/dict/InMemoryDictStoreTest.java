package com.richard.fyoung.customerwork.data.dict;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内字典存储单测（离线）：验证演示种子与查询语义（排序 / 未知类型 / 空入参）。
 * @author owlzhangfq@gmail.com
 */
class InMemoryDictStoreTest {

    private final InMemoryDictStore store = new InMemoryDictStore();

    @Test
    void listEnabledTypes_shouldContainOrderStatusSeed() {
        List<DictType> types = store.listEnabledTypes();
        assertTrue(types.stream().anyMatch(t -> "order_status".equals(t.dictType())), "演示种子应包含 order_status 类型");
    }

    @Test
    void findEnabledItems_shouldReturnOrderStatusSeeds_inSortOrder() {
        List<DictItem> items = store.findEnabledItems("order_status");
        assertEquals(7, items.size(), "订单状态种子应为七态");
        assertEquals("待支付", items.get(0).itemKey());
        assertEquals("已退款", items.get(6).itemKey());
        for (int i = 1; i < items.size(); i++) {
            assertTrue(items.get(i - 1).sort() <= items.get(i).sort(), "字典项应按 sort 升序");
        }
    }

    @Test
    void findEnabledItems_shouldReturnEmpty_forUnknownOrBlankType() {
        assertTrue(store.findEnabledItems("no_such_type").isEmpty());
        assertTrue(store.findEnabledItems("  ").isEmpty());
        assertTrue(store.findEnabledItems(null).isEmpty());
    }
}
