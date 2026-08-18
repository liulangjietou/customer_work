package com.richard.fyoung.customeradmin.subjectquota.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 后台登录用户的速率配额配置。
 *
 * <p>与客服端的 {@code customer-work.subject-quota.*} 分开配：两边的负载形态完全不同——
 * 后台用户跑的是智能体调试、VibeCoding 这类单次很重、频次很低的任务，
 * 拿 C 端的档位套上去只会把内部员工挡在门外。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@ConfigurationProperties(prefix = "admin.subject-quota")
public class AdminSubjectQuotaProperties {

    /** 是否开启后台用户配额判定。默认关闭，行为与引入前完全一致。 */
    private boolean enabled = false;

    /** 未单独分档的后台账号走这一档（等级定义在客服端库 {@code cw_subject_quota_level}）。 */
    private String defaultLevel = "admin-default";

    /**
     * 等级快照的惰性刷新间隔（毫秒）。
     *
     * <p>admin 刻意不开 {@code @EnableScheduling}（开了会把容器里所有 {@code @Scheduled} 一并激活），
     * 所以这边的快照只能靠读路径上的惰性刷新更新，不能用客服端那套定时轮询。</p>
     */
    private long lazyRefreshMs = 60000L;

    /** 用户等级绑定的本地缓存有效期（毫秒）：不缓存的话每个 AI 请求都要查一次 sys_user。 */
    private long levelCacheTtlMs = 60000L;

    /**
     * 参与判定的路径（Ant 模式）。
     *
     * <p>只覆盖真正调模型的入口——后台的列表、详情、附件下载不消耗额度。
     * 这与客服端刻意不同：那边为了给登录用户的所有接口加防刷闸门而覆盖了整个用户面，
     * 而后台用户是内部员工，防的是"调用失控"而不是"有人刷接口"。</p>
     */
    private List<String> pathPatterns = List.of(
        "/api/workspace/*/chat/stream",
        "/api/workspace/*/vibecoding/**",
        "/api/aiconfig/agent-task/**");
}
