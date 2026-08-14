package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.data.outbox.mapper.OutboxMessageMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class OutboxConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void jdbcTicketShouldRejectNonTransactionalMemoryOutbox() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getTicket().setStoreMode("jdbc");
        properties.getOutbox().setStoreMode("memory");
        ObjectProvider<OutboxMessageMapper> mapperProvider = mock(ObjectProvider.class);

        assertThrows(IllegalStateException.class,
            () -> new OutboxConfig().outboxStore(properties, mapperProvider));
    }
}
