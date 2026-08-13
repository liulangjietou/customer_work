package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB 接入参数（本模块独立执行器，appname/port 与 customer-work-starter 的
 * {@code customer-work.scheduler.xxl-job.*} 相互独立，避免两个进程注册成同一个执行器实例互相抢任务）。
 *
 * <p>默认关闭，本地无 XXL-JOB 调度中心也能正常启动；手动触发路径（{@code /trigger} 接口）
 * 完全不依赖本配置。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.xxl-job")
public class AdminXxlJobProperties {

    /** 是否启用 XXL-JOB 接入。 */
    private boolean enabled = false;
    /** XXL-JOB 调度中心地址，多个用逗号分隔。 */
    private String adminAddresses;
    /** 执行器 AppName（需与 XXL-JOB 控制台"执行器管理"注册的一致）。 */
    private String appname = "customer-admin-executor";
    /** 执行器内嵌 Netty Server 端口。 */
    private int port = 9998;
    /** 执行器通信 Token（需与调度中心一致；留空表示不校验）。 */
    private String accessToken;
    /** 执行器运行日志目录。不显式配置时 xxl-job-core 会退回内置默认值 {@code /data/applogs/...}，
     * 在容器根目录只读或无 root 权限的机器上会导致启动直接失败（FileSystemException），
     * 故这里给一个项目内可写的相对路径兜底，对齐 customer-work-starter 的
     * starter 侧同一套临时目录约定，目录名带 admin 前缀避免与 starter 侧执行器混用同一路径。 */
    private String logPath = RuntimeWorkDir.of("xxl-job-admin/jobhandler");
}
