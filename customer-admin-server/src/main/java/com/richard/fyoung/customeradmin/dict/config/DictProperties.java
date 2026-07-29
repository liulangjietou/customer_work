package com.richard.fyoung.customeradmin.dict.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 字典数据源连接参数：{@code admin.dict.*}。
 *
 * <p>字典两表（{@code cw_dict_type} / {@code cw_dict_item}）在<b>客服端库</b>（{@code agent_scope_customer_work}）
 * ——客服端运行时经 {@code DictStore}（store-mode=jdbc）读取的就是这两张表，后台只是这份数据的维护端。
 * 照内容风控先例单一数据真源、不做双写同步。该库不可达不影响 admin 启动（数据源惰性构建，
 * 见 {@link DictGatewayProvider}）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@ConfigurationProperties(prefix = "admin.dict")
public class DictProperties {

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

    /** 拼装 JDBC URL（与 admin 主库同款连接参数）。 */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
