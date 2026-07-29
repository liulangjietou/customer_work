package com.richard.fyoung.customeradmin.workspace.task.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /**
     * 启动时是否把上个进程遗留的非终态任务标记为失败。默认开启——那些任务的执行线程已随进程
     * 消失，留在库里就是永远转圈的假 RUNNING。多实例部署时需关闭（见
     * {@code MybatisTaskRepository#cleanupOrphanTasks} 的说明）。
     */
    private boolean cleanupOrphansOnStartup = true;
}
