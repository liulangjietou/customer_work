package com.richard.fyoung.customerwork.core.support;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkSchemaMigrator;
import com.richard.fyoung.customerwork.infra.config.properties.SessionProperties;
import com.richard.fyoung.customerwork.capability.approval.mapper.ApprovalMapper;
import com.richard.fyoung.customerwork.data.attachment.mapper.ChatAttachmentMapper;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallSegmentMapper;
import com.richard.fyoung.customerwork.data.chatlog.mapper.ChatMessageMapper;
import com.richard.fyoung.customerwork.capability.dialog.mapper.DialogStageMapper;
import com.richard.fyoung.customerwork.data.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.data.dict.mapper.DictTypeMapper;
import com.richard.fyoung.customerwork.capability.feedback.mapper.FeedbackMapper;
import com.richard.fyoung.customerwork.capability.handoff.mapper.HandoffMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.HarnessMemoryMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.MemoryConsentMapper;
import com.richard.fyoung.customerwork.observability.mapper.AuditLogMapper;
import com.richard.fyoung.customerwork.capability.routing.mapper.SeatAgentMapper;
import com.richard.fyoung.customerwork.capability.semanticcache.mapper.SemanticCacheMapper;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordMapper;
import com.richard.fyoung.customerwork.capability.slotfilling.mapper.SlotFillingMapper;
import com.richard.fyoung.customerwork.data.skill.mapper.SkillFileMapper;
import com.richard.fyoung.customerwork.data.skill.mapper.SkillMapper;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.data.ticket.mapper.TicketMapper;
import com.richard.fyoung.customerwork.data.outbox.mapper.OutboxMessageMapper;
import com.richard.fyoung.customerwork.capability.deadletter.mapper.DeadLetterMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.ComplaintMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberAccountLogMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.MemberMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.ProductMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import com.richard.fyoung.customerwork.data.user.mapper.UserMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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
    private static final String TEST_DATABASE_ENV = "CUSTOMER_WORK_TEST_DATABASE";

    /**
     * 全部 Mapper 接口清单：脱离 Spring 容器时手工注册（生产由 @MapperScan 逐个 addMapper）。
     * 含无 XML 的纯 BaseMapper（如 TicketEventMapper/UserMapper），仅靠 mapperLocations 无法注册。
     */
    private static final Class<?>[] ALL_MAPPERS = {
        ApprovalMapper.class, SlotFillingMapper.class, DialogStageMapper.class, HandoffMapper.class,
        FeedbackMapper.class, UserMapper.class, TicketMapper.class, TicketEventMapper.class,
        ChatMessageMapper.class, AuditLogMapper.class, OrderMapper.class, ProductMapper.class,
        RefundMapper.class, InvoiceRequestMapper.class, MemberMapper.class, MemberAccountLogMapper.class,
        ComplaintMapper.class, KnowledgeMapper.class, ChatAttachmentMapper.class,
        SensitiveWordMapper.class, SeatAgentMapper.class,
        AgentCallLogMapper.class, AgentCallSegmentMapper.class,
        DictTypeMapper.class, DictItemMapper.class,
        LongTermMemoryMapper.class, FactLogMapper.class, HarnessMemoryMapper.class,
        MemoryConsentMapper.class,
        SemanticCacheMapper.class,
        SkillMapper.class, SkillFileMapper.class,
        DeadLetterMapper.class, OutboxMessageMapper.class
    };

    private MybatisTestSupport() {
    }

    /** 用本机 MySQL 构建测试数据源；可通过 CUSTOMER_WORK_TEST_DATABASE 隔离个人业务库。 */
    public static HikariDataSource mysqlDataSource(String poolName) {
        SessionProperties.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setDatabase(System.getenv().getOrDefault(TEST_DATABASE_ENV, "agent_scope_customer_work"));
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

    /** 确保业务表结构与种子已就绪：测试与生产使用同一套 Flyway 迁移，不维护第二套补列逻辑。 */
    public static void ensureSchema(DataSource dataSource) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getSession().getMysql().setMigrationEnabled(true);
        new CustomerWorkSchemaMigrator(dataSource, properties).afterPropertiesSet();
    }
}
