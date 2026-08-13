package com.richard.fyoung.customerwork.capability.deadletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 死信重投巡检器：周期性把到期的死信重做一遍。
 *
 * <p>队列只有"记下来"是不够的——记下来没人重投，和只记 error 没有本质区别，
 * 只是把日志换了个地方存。</p>
 * @author owlzhangfq@gmail.com
 */
public class DeadLetterRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRetryScheduler.class);

    private final DeadLetterService deadLetterService;

    public DeadLetterRetryScheduler(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @Scheduled(fixedDelayString = "${customer-work.dead-letter.scan-interval-ms:60000}")
    public void retryDue() {
        try {
            deadLetterService.retryDue();
        } catch (Exception e) {
            // 巡检自身出错不能让调度停摆——下一轮还得继续跑
            log.error("dead letter retry round failed, errorCode={}", "DEADLETTER-SCAN-FAIL", e);
        }
    }
}
