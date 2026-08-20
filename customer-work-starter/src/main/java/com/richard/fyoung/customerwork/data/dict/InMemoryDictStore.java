package com.richard.fyoung.customerwork.data.dict;

import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 进程内字典存储（默认实现，离线可测）。
 *
 * <p>种子与 {@code customer-work-schema.sql} 中 {@code cw_dict_type} / {@code cw_dict_item}
 * 的演示种子保持一致（order_status 订单状态七态），保证 memory / jdbc 两种模式下
 * 消费方看到同一份演示数据。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryDictStore implements DictStore {

    private final List<DictType> types;
    private final List<DictItem> items;

    public InMemoryDictStore() {
        this.types = List.of(new DictType("order_status", "订单状态", "用户订单状态筛选项（与后端返回的中文文案一致）", true));
        List<DictItem> seeds = new ArrayList<>();
        String[] statuses = {OrderStatuses.PENDING_PAYMENT, OrderStatuses.PAID, OrderStatuses.PENDING_SHIPMENT,
            OrderStatuses.SHIPPED, OrderStatuses.RECEIVED, OrderStatuses.CANCELLED, OrderStatuses.REFUNDED};
        for (int i = 0; i < statuses.length; i++) {
            seeds.add(new DictItem((long) (i + 1), "order_status", statuses[i], statuses[i], i + 1, true, null));
        }
        this.items = List.copyOf(seeds);
    }

    @Override
    public List<DictType> listEnabledTypes() {
        return types;
    }

    @Override
    public List<DictItem> findEnabledItems(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            return List.of();
        }
        return items.stream()
            .filter(item -> dictType.equals(item.dictType()))
            .sorted(Comparator.comparingInt(DictItem::sort))
            .toList();
    }
}
