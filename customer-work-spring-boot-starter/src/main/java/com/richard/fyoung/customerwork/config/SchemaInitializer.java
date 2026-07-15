package com.richard.fyoung.customerwork.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 业务表结构与种子数据统一初始化器（取代此前散落在 15 个 Jdbc 类里的 {@code ensureTable} + {@code seed}）。
 *
 * <p>启动时按 {@code session.mysql.auto-create} 开关执行 {@value #SCHEMA_LOCATION}——集中了工单/审批/
 * 槽位/对话阶段/人机切换/反馈/审计/用户/聊天日志九域，以及订单/商品/售后/会员/投诉/知识库六个工具后端演示表的
 * {@code CREATE TABLE IF NOT EXISTS} 与 {@code INSERT IGNORE} 种子，内容与 {@code mysql/schema.sql}（DBA 手册）一致。</p>
 *
 * <p>仅在 {@link CustomerWorkPersistenceConfig} 激活（任一域 jdbc）时装配；建表失败只 error 记录不阻断启动
 * （与旧 ensureTable 语义一致：MySQL 冷启动瞬时不可达时后续读写各自重试）。</p>
 * @author owlzhangfq@gmail.com
 */
public class SchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final String SCHEMA_LOCATION = "customerwork/schema/customer-work-schema.sql";

    private final DataSource dataSource;
    private final boolean autoCreate;

    public SchemaInitializer(DataSource dataSource, CustomerWorkProperties properties) {
        this.dataSource = dataSource;
        this.autoCreate = properties.getSession().getMysql().isAutoCreate();
    }

    @Override
    public void afterPropertiesSet() {
        if (!autoCreate) {
            log.info("customer-work schema auto-create disabled (session.mysql.auto-create=false), skip");
            return;
        }
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(SCHEMA_LOCATION));
            populator.setSeparator(";");
            populator.setCommentPrefixes("--");
            populator.execute(dataSource);
            log.info("customer-work business schema ready (tables + seeds applied)");
        } catch (Exception e) {
            // 捕获 Exception 而非 SQLException：HikariCP 首次取连接失败抛 RuntimeException（PoolInitialization）；
            // 建表失败不阻断 Bean 装配，与旧各域 ensureTable 一致
            log.error("customer-work schema init failed, code={}", "PERSISTENCE-SCHEMA-INIT-FAIL", e);
        }
    }
}
