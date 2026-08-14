package com.richard.fyoung.customerwork.data.outbox;

/**
 * Outbox 消费 Handler SPI。
 *
 * <p>投递语义是至少一次，Handler 必须以 {@link OutboxMessage#getId()} 作为幂等键。</p>
 */
public interface OutboxHandler {

    String type();

    void handle(OutboxMessage message) throws Exception;
}
