package com.richard.fyoung.customeradmin.contentguard.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordExtMapper;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordHitLogExtMapper;
import com.richard.fyoung.customerwork.security.ratelimit.mapper.RateLimitRuleMapper;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordHitLogMapper;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 为客服端库数据源装配一套内容风控门面（{@link ContentGuardGateway}）。
 *
 * <p>手法与 {@code AgentCallStatsGatewayFactory} 一致：用 {@link MybatisSqlSessionFactoryBean} 现场构建
 * <b>专用</b> {@link SqlSessionFactory}（不暴露为 Spring Bean，免得 MP 自动装配的主
 * {@code SqlSessionFactory} 因 {@code @ConditionalOnMissingBean} 退避、连带把主 Mapper 扫描搞坏）。
 * 加载 starter jar 内的三张 Mapper XML 与 admin 自带的两张 ext XML——XML 的 namespace 绑定即完成
 * Mapper 接口注册与 MP BaseMapper CRUD 注入，不需要 {@code @MapperScan}。</p>
 *
 * <p>{@link SensitiveWordHitLogMapper} 没有自己的 XML（只用 BaseMapper 的 CRUD），故通过
 * {@code addMapper} 直接注册接口。</p>
 * @author owlzhangfq@gmail.com
 */
final class ContentGuardGatewayFactory {

    /** starter jar 内的敏感词 Mapper XML（classpath*: 才能命中 jar 内资源）。 */
    private static final String STARTER_WORD_XML = "classpath*:customerwork/mapper/SensitiveWordMapper.xml";
    /** starter jar 内的限流规则 Mapper XML。 */
    private static final String STARTER_RULE_XML = "classpath*:customerwork/mapper/RateLimitRuleMapper.xml";
    /** admin 自带的读侧扩展 XML（放 /contentguard 下，避开主 MP 默认的 /mapper 扫描）。 */
    private static final String EXT_XML = "classpath*:contentguard/*.xml";

    private ContentGuardGatewayFactory() {
    }

    /** 按数据源装配一套门面。XML 解析/工厂构建阶段不连库，首次查询时才真正取连接。 */
    static ContentGuardGateway build(DataSource dataSource) {
        try {
            SqlSessionFactory factory = buildSqlSessionFactory(dataSource);
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            return new ContentGuardGateway(
                template.getMapper(SensitiveWordMapper.class),
                template.getMapper(SensitiveWordExtMapper.class),
                template.getMapper(RateLimitRuleMapper.class),
                template.getMapper(SensitiveWordHitLogMapper.class),
                template.getMapper(SensitiveWordHitLogExtMapper.class));
        } catch (Exception e) {
            throw new IllegalStateException("failed to build content guard gateway", e);
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // 列名下划线、DO 字段驼峰，必须开启映射（created_at_ms -> createdAtMs）
        configuration.setMapUnderscoreToCamelCase(true);
        // 命中日志 Mapper 无 XML，只用 BaseMapper 的 CRUD，直接注册接口即可
        configuration.addMapper(SensitiveWordHitLogMapper.class);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(resolveMapperXml());
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    private static Resource[] resolveMapperXml() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>();
        resources.addAll(Arrays.asList(resolver.getResources(STARTER_WORD_XML)));
        resources.addAll(Arrays.asList(resolver.getResources(STARTER_RULE_XML)));
        resources.addAll(Arrays.asList(resolver.getResources(EXT_XML)));
        return resources.toArray(new Resource[0]);
    }
}
