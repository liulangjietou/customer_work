package com.richard.fyoung.customerwork.infra.gateway;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * 一条数据源上的独立 MyBatis 运行环境（专用 {@link SqlSessionFactory} + Mapper 代理），
 * 可能自带连接池（跨库场景），也可能借用宿主已有的数据源（同库另配一套 Mapper 环境的场景）。
 *
 * <p><b>专用 SqlSessionFactory 刻意不暴露为 Spring Bean</b>：一旦成为容器里的 {@code SqlSessionFactory}/
 * {@code SqlSessionTemplate} Bean，宿主自动装配的主 factory 会因 {@code @ConditionalOnMissingBean} 退避，
 * 主 Mapper 扫描随之被搞坏。所以本对象由静态工厂 {@link CrossDbGateways} 现场 new 出来、由持有者管生命周期。</p>
 *
 * <p>Mapper 代理由 {@link SqlSessionTemplate} 提供：非 Spring 事务下每次操作自动提交，
 * 与宿主主库的事务完全隔离（跨库本就不该被同一个事务串起来）。</p>
 * @author owlzhangfq@gmail.com
 */
public final class CrossDbGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CrossDbGateway.class);

    private final String name;
    private final String jdbcUrl;
    private final DataSource dataSource;
    /** 自建的池；借用宿主数据源时为 null——借来的池绝不能由本对象关闭。 */
    private final HikariDataSource ownedDataSource;
    private final SqlSessionFactory sqlSessionFactory;
    private final SqlSessionTemplate sqlSessionTemplate;

    CrossDbGateway(String name, String jdbcUrl, DataSource dataSource,
                   HikariDataSource ownedDataSource, SqlSessionFactory sqlSessionFactory) {
        this.name = name;
        this.jdbcUrl = jdbcUrl;
        this.dataSource = dataSource;
        this.ownedDataSource = ownedDataSource;
        this.sqlSessionFactory = sqlSessionFactory;
        this.sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
    }

    /** 取 Mapper 代理；类型必须是构建时通过接口注册或 XML namespace 绑定过的 Mapper。 */
    public <T> T getMapper(Class<T> mapperType) {
        return sqlSessionTemplate.getMapper(mapperType);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public SqlSessionFactory sqlSessionFactory() {
        return sqlSessionFactory;
    }

    /** 池名（自建池）或标识（借用数据源），只用于日志与异常定位。 */
    public String name() {
        return name;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /**
     * 连通性探测：取一条连接随即归还，拿得到即视为可达。
     *
     * @throws CrossDbUnavailableException 库不可达（调用方据此给出"依赖库暂不可用"的明确提示）
     */
    public void probe() {
        CrossDbGateways.probeDataSource(dataSource, name, jdbcUrl);
    }

    /** 关闭自建连接池；借用宿主数据源时什么都不做（池归宿主容器管）。 */
    @Override
    public void close() {
        if (ownedDataSource == null) {
            return;
        }
        ownedDataSource.close();
        log.info("cross-db gateway closed, pool={}", name);
    }
}
