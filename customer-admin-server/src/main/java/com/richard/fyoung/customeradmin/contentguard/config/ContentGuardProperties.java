package com.richard.fyoung.customeradmin.contentguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内容风控数据源连接参数：{@code admin.content-guard.*}。
 *
 * <p>敏感词库、限流规则、命中日志三张表都在<b>客服端库</b>（{@code agent_scope_customer_work}）——
 * 那是运行时真正读它们的进程所在的库，后台只是这份数据的维护端。刻意<b>不</b>在 admin 库另存一份再同步：
 * 双写必然出现"后台显示已停用、客服端仍在拦"这类不一致，而风控配置的不一致是要出事故的。</p>
 *
 * <p>该库不可达不影响 admin 启动——数据源惰性构建、首次访问才连（见 {@link ContentGuardGatewayProvider}）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@ConfigurationProperties(prefix = "admin.content-guard")
public class ContentGuardProperties {

    /** 数据库主机。 */
    private String host = "localhost";

    /** 数据库端口。 */
    private int port = 3306;

    /** 库名。 */
    private String database = "agent_scope_customer_work";

    /** 用户名。 */
    private String username = "root";

    /** 密码。 */
    private String password = "root";

    /**
     * 是否给 admin 自己的 workspace 智能体（对话 / VibeCoding）挂敏感词过滤。默认关闭。
     *
     * <p>关闭时整套过滤 Bean 不装配，admin 只做词库的管理界面、不参与拦截。开启后与 8080 客服链路
     * <b>共用同一份词库</b>（都读客服端库那张表），运营维护一次两边同时生效。</p>
     *
     * <p>开启的代价要知道：过滤器构造时会读一次词表，客服端库此刻不可达则进入 fail-closed
     * （拦截一切）——这是敏感词的既定语义，安全优先。刷新器随后每 {@code refreshIntervalMs} 重试一次，
     * 库恢复即自动脱离，无需重启 admin。</p>
     */
    private boolean agentFilterEnabled = false;

    /** 过滤生效方向：INBOUND（仅用户输入）| OUTBOUND（仅模型输出）| BOTH（默认）。 */
    private String direction = "BOTH";

    /** 词表刷新轮询间隔（毫秒，默认 60s）。 */
    private long refreshIntervalMs = 60_000L;

    /** admin 侧命中是否也落命中日志表（与 8080 侧的命中混在同一张表，靠 agent_name 区分）。默认关闭。 */
    private boolean hitLogEnabled = false;

    /** 命中日志留存的原文片段长度上限（字符）。 */
    private int hitLogSnippetMaxLength = 64;

    /** 拼装 JDBC URL（与 admin 主库同款连接参数）。 */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
