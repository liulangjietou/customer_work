package com.richard.fyoung.customerwork.data.outbox;

import java.util.List;

/** Outbox 存储 SPI：JDBC 实现通过条件更新租约保证多副本只有一个消费者拿到消息。 */
public interface OutboxStore {

    void save(OutboxMessage message);

    List<OutboxMessage> claimDue(String owner, long nowMs, long leaseUntilMs, int limit);

    boolean complete(OutboxMessage message, String owner);

    long count(OutboxStatus status);
}
