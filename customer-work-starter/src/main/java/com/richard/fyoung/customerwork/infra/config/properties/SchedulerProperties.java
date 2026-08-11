package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 AgentScope 的定时任务调度配置。
 *
 * <p>周期（cron/固定频率）、启停、失败重试统一交给 XXL-JOB 控制台管理，本配置只负责"启动时
 * 把哪些固定任务注册成 XXL-JOB JobHandler"，避免应用内定时器与控制台配置的周期"两处真相"
 * 互相打架。默认关闭，本地无 XXL-JOB 调度中心也能正常启动。</p>
 */
@Data
public class SchedulerProperties {
    /** XXL-JOB 接入配置。 */
    private final XxlJob xxlJob = new XxlJob();
    /** 固定任务列表：启动时逐个注册为 XXL-JOB JobHandler（handler 名 = 任务 name）。 */
    private List<Task> tasks = new ArrayList<>();
    /** 单次任务执行超时（秒）：Agent 同步调用的硬性超时兜底，避免调度线程被永久占用。 */
    private long executeTimeoutSeconds = 300;

    @Data
    public static class XxlJob {
        /** 是否启用 XXL-JOB 接入（默认关闭，本地无调度中心也能正常启动）。 */
        private boolean enabled = false;
        /** XXL-JOB 调度中心地址，多个用逗号分隔。 */
        private String adminAddresses;
        /** 执行器 AppName（需与 XXL-JOB 控制台"执行器管理"注册的一致）。 */
        private String appname = "customer-work-executor";
        /** 执行器内嵌 Netty Server 端口。 */
        private int port = 9999;
        /** 执行器通信 Token（需与调度中心一致；留空表示不校验）。 */
        private String accessToken;
        /** 执行器运行日志存储路径。 */
        private String logPath = "./data/xxl-job/jobhandler";
    }

    /** 固定任务定义：任务名即 XXL-JOB JobHandler 名，需与控制台"任务管理"里的 JobHandler 一致。 */
    @Data
    public static class Task {
        /** 任务名（= XXL-JOB JobHandler 名，全局唯一）。 */
        private String name;
        /** Agent 系统提示词。 */
        private String sysPrompt;
    }
}
