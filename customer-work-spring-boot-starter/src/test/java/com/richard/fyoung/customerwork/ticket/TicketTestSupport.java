package com.richard.fyoung.customerwork.ticket;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 工单测试辅助：构造一个基于固定监听器列表的 {@link ObjectProvider}，供 {@code TicketService} 广播用。
 *
 * <p>{@code stream()} 每次返回新的流，故可被多次 {@code forEach} 消费（每个事件一次广播）。</p>
 * @author owlzhangfq@gmail.com
 */
final class TicketTestSupport {

    private TicketTestSupport() {
    }

    static ObjectProvider<TicketEventListener> providerOf(TicketEventListener... listeners) {
        List<TicketEventListener> list = Arrays.asList(listeners);
        return new ObjectProvider<>() {
            @Override
            public TicketEventListener getObject() {
                if (list.size() != 1) {
                    throw new IllegalStateException("not exactly one listener");
                }
                return list.get(0);
            }

            @Override
            public TicketEventListener getObject(Object... args) {
                return getObject();
            }

            @Override
            public TicketEventListener getIfAvailable() {
                return list.isEmpty() ? null : list.get(0);
            }

            @Override
            public TicketEventListener getIfUnique() {
                return list.size() == 1 ? list.get(0) : null;
            }

            @Override
            public Stream<TicketEventListener> stream() {
                return list.stream();
            }

            @Override
            public Stream<TicketEventListener> orderedStream() {
                return list.stream();
            }
        };
    }
}
