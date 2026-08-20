package com.richard.fyoung.customeradmin.common.gateway;

/**
 * 客服端库的连接信息来源。
 *
 * <p>{@link CustomerWorkFacade} 只需要这三项就能建池，不关心它们来自哪个
 * {@code @ConfigurationProperties}。多数能力域复用 {@code admin.content-guard.*} 的那一份
 * （这些表同在客服端库，再配一套连接参数只会多一处要同步维护的配置），
 * 但调用统计走的是 {@code admin.agent-call-stats.app.*} 自己的一份——
 * 抽成接口正是为了让这种差异不必牺牲门面的统一。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public interface CustomerWorkDbConnection {

    /** 完整 JDBC URL（各属性类按自己的 host/port/database 拼装）。 */
    String jdbcUrl();

    String getUsername();

    String getPassword();
}
