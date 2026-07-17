package com.richard.fyoung.customerwork.support;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.approval.mapper.ApprovalMapper;
import com.richard.fyoung.customerwork.attachment.mapper.ChatAttachmentMapper;
import com.richard.fyoung.customerwork.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.dialog.mapper.DialogStageMapper;
import com.richard.fyoung.customerwork.feedback.mapper.FeedbackMapper;
import com.richard.fyoung.customerwork.handoff.mapper.HandoffMapper;
import com.richard.fyoung.customerwork.observability.mapper.AuditLogMapper;
import com.richard.fyoung.customerwork.slotfilling.mapper.SlotFillingMapper;
import com.richard.fyoung.customerwork.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.ticket.mapper.TicketMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.ComplaintMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberAccountLogMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.ProductMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import com.richard.fyoung.customerwork.user.mapper.UserMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * MyBatis 持久层测试支撑：脱离 Spring 容器，用与 {@code CustomerWorkPersistenceConfig} 一致的方式
 * （MybatisSqlSessionFactoryBean + 同一批 mapper XML + 下划线转驼峰 + 分页插件）从一个 DataSource
 * 构建 {@link SqlSessionTemplate}，供各 {@code Mybatis*StoreTest} / {@code Mybatis*BackendTest} 取 Mapper。
 *
 * <p>{@link SqlSessionTemplate} 在无 Spring 事务时逐语句自动提交，语义等同生产环境（无 {@code @Transactional}）。</p>
 * @author owlzhangfq@gmail.com
 */
public final class MybatisTestSupport {

    private static final String MAPPER_LOCATIONS = "classpath*:customerwork/mapper/*.xml";

    /**
     * 全部 Mapper 接口清单：脱离 Spring 容器时手工注册（生产由 @MapperScan 逐个 addMapper）。
     * 含无 XML 的纯 BaseMapper（如 TicketEventMapper/UserMapper），仅靠 mapperLocations 无法注册。
     */
    private static final Class<?>[] ALL_MAPPERS = {
        ApprovalMapper.class, SlotFillingMapper.class, DialogStageMapper.class, HandoffMapper.class,
        FeedbackMapper.class, UserMapper.class, TicketMapper.class, TicketEventMapper.class,
        ChatMessageMapper.class, AuditLogMapper.class, OrderMapper.class, ProductMapper.class,
        RefundMapper.class, InvoiceRequestMapper.class, MemberMapper.class, MemberAccountLogMapper.class,
        ComplaintMapper.class, KnowledgeMapper.class, ChatAttachmentMapper.class
    };

    private MybatisTestSupport() {
    }

    /** 用本机 MySQL（root/root，库 agent_scope_customer_work）构建 Hikari 数据源。 */
    public static HikariDataSource mysqlDataSource(String poolName) {
        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setDatabase("agent_scope_customer_work");
        cfg.setUsername("root");
        cfg.setPassword("root");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.resolveJdbcUrl());
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setMaximumPoolSize(3);
        ds.setPoolName(poolName);
        return ds;
    }

    /** 基于给定 DataSource 构建与生产同构的 SqlSessionTemplate。 */
    public static SqlSessionTemplate template(DataSource dataSource) {
        try {
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATIONS));
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factoryBean.setConfiguration(configuration);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            factoryBean.setPlugins(interceptor);
            SqlSessionFactory factory = factoryBean.getObject();
            if (factory == null) {
                throw new IllegalStateException("failed to build SqlSessionFactory for test");
            }
            // 注册全部 Mapper 接口（无 XML 的纯 BaseMapper 不会被 mapperLocations 注册；带 XML 的已注册，hasMapper 去重）
            Configuration sessionConfig = factory.getConfiguration();
            for (Class<?> mapperType : ALL_MAPPERS) {
                if (!sessionConfig.hasMapper(mapperType)) {
                    sessionConfig.addMapper(mapperType);
                }
            }
            return new SqlSessionTemplate(factory);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build test SqlSessionTemplate", e);
        }
    }

    /** 便捷方法：从 DataSource 直接取某个 Mapper。 */
    public static <T> T mapper(DataSource dataSource, Class<T> mapperType) {
        return template(dataSource).getMapper(mapperType);
    }

    /**
     * 确保业务表结构与种子已就绪（幂等）：执行与生产 {@code SchemaInitializer} 相同的建表脚本。
     * 替代旧 {@code JdbcXxxStore} 构造里的 ensureTable/seed。
     */
    public static void ensureSchema(DataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("customerwork/schema/customer-work-schema.sql"));
        populator.setSeparator(";");
        populator.setCommentPrefixes("--");
        populator.execute(dataSource);
    }
}
