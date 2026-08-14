package com.richard.fyoung.customerwork.data.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Outbox 周期投递驱动。 */
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxService service;

    public OutboxDispatcher(OutboxService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${customer-work.outbox.scan-interval-ms:1000}")
    public void dispatch() {
        try {
            service.dispatchDue();
        } catch (Exception e) {
            log.error("outbox dispatch round failed, code={}", "OUTBOX-SCAN-FAIL", e);
        }
    }
}
