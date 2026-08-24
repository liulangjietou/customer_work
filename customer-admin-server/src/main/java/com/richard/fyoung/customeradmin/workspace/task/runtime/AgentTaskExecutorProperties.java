package com.richard.fyoung.customeradmin.workspace.task.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 后台委派任务执行参数：{@code admin.agent-task.*}。
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.agent-task")
public class AgentTaskExecutorProperties {

    /**
     * 任务执行线程池大小。默认 4：后台任务每个都会跑一整轮子智能体（含模型调用），
     * 是长耗时低频操作，池子开大只会同时打爆模型配额，并发靠的是任务本身异步而不是池子大小。
     */
    private int poolSize = 4;

    /** Pod/进程所有者标识；留空时按 HOSTNAME + 进程 ID + 随机后缀生成。 */
    private String ownerId;

    /** 单次所有权租约秒数。 */
    private int leaseSeconds = 90;

    /** 活跃任务心跳间隔秒数，必须显著小于 leaseSeconds。 */
    private int heartbeatSeconds = 20;

    /** 过期租约扫描间隔秒数。 */
    private int recoveryScanSeconds = 15;

    /** 每次最多接管的任务数。 */
    private int recoveryBatchSize = 20;

    /** 首次执行加宕机重试的总次数上限。 */
    private int maxAttempts = 3;

    public String resolveOwnerId() {
        if (ownerId != null && !ownerId.isBlank()) {
            return ownerId.trim();
        }
        String host = System.getenv("HOSTNAME");
        String node = host == null || host.isBlank() ? "local" : host.trim();
        return node + "-" + ProcessHandle.current().pid() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    }
}
