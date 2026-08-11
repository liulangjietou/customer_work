package com.richard.fyoung.customerwork.infra.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import com.richard.fyoung.customerwork.infra.config.properties.SessionProperties;
import com.richard.fyoung.customerwork.infra.config.properties.TenantProperties;

/**
 * customer-work 业务持久层的独立 MyBatis-Plus 环境（全部显式命名 + Ref 绑定，不依赖 MyBatis 自动装配）。
 *
 * <p><b>为何独立且显式：</b>本 starter 是 WebFlux 宿主，且下游（含自带 MyBatis 环境的应用）需要"零干扰"。
 * 故不引入 {@code mybatis-plus-spring-boot3-starter}（会自动扫描全局 Mapper、共用宿主 DataSource），
 * 而是自建一套 {@code customerWorkDataSource} / {@code customerWorkSqlSessionFactory} /
 * {@code customerWorkSqlSessionTemplate}，并用 {@link MapperScan#sqlSessionTemplateRef()} 把
 * {@code com.richard.fyoung.customerwork.**.mapper} 下的 Mapper 精确绑定到本环境的 template——
 * 与宿主自己的 SqlSessionFactory / MapperScan 互不影响。</p>
 *
 * <p><b>激活条件（{@link PersistenceJdbcCondition}）：</b>任一业务域 {@code store-mode=jdbc} 或
 * {@code tool-backend.mode=jdbc} 时才装配；全 memory/mock 时本类整体不加载，宿主无需 MySQL 即可启动。
 * 数据源用 HikariCP 惰性连接（构造不建连），故装配本环境不等于启动即连库。</p>
 *
 * <p>{@code customerWorkDataSource} 用 {@code @ConditionalOnMissingBean(name)} 允许宿主以同名 Bean 覆盖
 * （复用宿主已有连接池）。建表与种子由 {@link SchemaInitializer} 统一负责，Mapper 只表达读写 SQL。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@Conditional(PersistenceJdbcCondition.class)
@MapperScan(
    basePackages = "com.richard.fyoung.customerwork.**.mapper",
    sqlSessionTemplateRef = "customerWorkSqlSessionTemplate")
public class CustomerWorkPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomerWorkPersistenceConfig.class);

    /** Mapper XML 位置：所有域的 *.xml 统一放在该目录。 */
    private static final String MAPPER_LOCATIONS = "classpath*:customerwork/mapper/*.xml";

    private static final String DATA_SOURCE_BEAN = "customerWorkDataSource";

    /**
     * 业务持久化独立数据源（复用 {@code session.mysql.*} 连接配置，与会话状态存储同库不同池）。
     *
     * <p>惰性建连（HikariCP 首次取连接才连库）；下游可声明同名 Bean 覆盖以复用自有连接池。</p>
     */
    @Bean(DATA_SOURCE_BEAN)
    @ConditionalOnMissingBean(name = DATA_SOURCE_BEAN)
    public DataSource customerWorkDataSource(CustomerWorkProperties properties) {
        SessionProperties.Mysql m = properties.getSession().getMysql();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(10);
        ds.setPoolName("customer-work-persistence-pool");
        log.info("customer-work persistence datasource ready (lazy), url={}", m.resolveJdbcUrl());
        return ds;
    }

    /**
     * 本环境专用 SqlSessionFactory：绑定 {@code customerWorkDataSource}、加载 {@value #MAPPER_LOCATIONS}、
     * 开启下划线转驼峰、注册分页插件（工单列表服务端分页用）。
     */
    @Bean("customerWorkSqlSessionFactory")
    @ConditionalOnMissingBean(name = "customerWorkSqlSessionFactory")
    public SqlSessionFactory customerWorkSqlSessionFactory(DataSource customerWorkDataSource,
                                                           CustomerWorkProperties properties) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(customerWorkDataSource);
        factoryBean.setMapperLocations(resolveMapperLocations());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);

        // 不打 mybatis-plus 启动横幅：宿主若自带 MyBatis-Plus 环境已打过，本环境再打就是重复噪音
        //（与 CrossDbGateways 的处理一致）。defaults() 与不设置时框架内部的兜底同源，仅关 banner。
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setBanner(false);
        factoryBean.setGlobalConfig(globalConfig);

        factoryBean.setPlugins(buildInterceptor(properties));
        return factoryBean.getObject();
    }

    /** 本环境专用 SqlSessionTemplate（无 Spring 事务时逐语句自动提交，语义等同原裸 JDBC 的 autoCommit 连接）。 */
    @Bean("customerWorkSqlSessionTemplate")
    @ConditionalOnMissingBean(name = "customerWorkSqlSessionTemplate")
    public SqlSessionTemplate customerWorkSqlSessionTemplate(SqlSessionFactory customerWorkSqlSessionFactory) {
        return new SqlSessionTemplate(customerWorkSqlSessionFactory);
    }

    /** 统一建表 + 种子初始化器（随本持久化环境装配，按 session.mysql.auto-create 决定是否执行）。 */
    @Bean
    @ConditionalOnMissingBean(SchemaInitializer.class)
    public SchemaInitializer customerWorkSchemaInitializer(DataSource customerWorkDataSource,
                                                           CustomerWorkProperties properties) {
        return new SchemaInitializer(customerWorkDataSource, properties);
    }

    /**
     * 插件链：租户过滤 + 分页（MySQL 方言，工单 findPage 用 {@code Page} 服务端分页）。
     *
     * <p><b>租户拦截器必须排在分页之前</b>：MyBatis-Plus 按注册顺序执行内部拦截器，
     * 分页插件会先执行 count 查询，若租户条件尚未拼上，count 与实际数据页的口径就会不一致。</p>
     */
    private MybatisPlusInterceptor buildInterceptor(CustomerWorkProperties properties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        TenantProperties tenant = properties.getTenant();
        if (tenant.isEnabled()) {
            interceptor.addInnerInterceptor(
                TenantInterceptors.build(tenant.getColumnName(), tenant.getIgnoredTables()));
            log.info("customer-work tenant line filter enabled, column={}", tenant.getColumnName());
        }
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    private Resource[] resolveMapperLocations() throws java.io.IOException {
        return new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATIONS);
    }
}
