package com.richard.fyoung.customeradmin.workspace.callstats.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsExtMapper;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customerwork.calllog.AgentCallLogStore;
import com.richard.fyoung.customerwork.calllog.MybatisAgentCallLogStore;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallSegmentMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 为某个数据源装配一套独立的调用统计门面（{@link AgentCallStatsGateway}）。
 *
 * <p>关键点：用 MyBatis-Plus 的 {@link MybatisSqlSessionFactoryBean} 现场构建一个<b>专用</b>
 * {@link SqlSessionFactory}（不作为 Spring Bean 暴露，避免让 MP 自动装配的主
 * {@code SqlSessionFactory}/{@code SqlSessionTemplate} 因 {@code @ConditionalOnMissingBean} 退避而
 * 破坏主 Mapper 扫描）。工厂加载 starter jar 内的两张 Mapper XML（{@code customerwork/mapper/*.xml}）
 * 与 admin 自带的 ext XML（{@code callstats/*.xml}）——XML 的 namespace 绑定即完成 Mapper 接口注册与
 * MP BaseMapper CRUD 注入，无需 {@code @MapperScan}。Mapper 代理由 {@link SqlSessionTemplate} 提供，
 * 其在非 Spring 事务下每次操作自动提交（写入/查询均在本源独立提交，与主库事务完全隔离）。</p>
 * @author owlzhangfq@gmail.com
 */
final class AgentCallStatsGatewayFactory {

    /** starter jar 内的调用日志主表 Mapper XML（classpath*: 才能命中 jar 内资源）。 */
    private static final String STARTER_LOG_XML = "classpath*:customerwork/mapper/AgentCallLogMapper.xml";
    /** starter jar 内的调用分段 Mapper XML。 */
    private static final String STARTER_SEGMENT_XML = "classpath*:customerwork/mapper/AgentCallSegmentMapper.xml";
    /** admin 自带的读侧扩展 Mapper XML（放 /callstats 下，避开主 MP 默认的 /mapper 扫描）。 */
    private static final String EXT_XML = "classpath*:callstats/AgentCallStatsExtMapper.xml";

    private AgentCallStatsGatewayFactory() {
    }

    /** 按数据源装配一套门面。XML 解析/工厂构建阶段不连库，首次查询时才真正取连接。 */
    static AgentCallStatsGateway build(DataSource dataSource) {
        try {
            SqlSessionFactory factory = buildSqlSessionFactory(dataSource);
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            AgentCallLogMapper logMapper = template.getMapper(AgentCallLogMapper.class);
            AgentCallSegmentMapper segmentMapper = template.getMapper(AgentCallSegmentMapper.class);
            AgentCallStatsExtMapper extMapper = template.getMapper(AgentCallStatsExtMapper.class);
            AgentCallLogStore store = new MybatisAgentCallLogStore(logMapper, segmentMapper);
            return new AgentCallStatsGateway(extMapper, logMapper, segmentMapper, store);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build agent call stats gateway", e);
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // starter/ext XML 均用列别名下划线命名（request_id 等），需开启驼峰映射到 DO 的驼峰字段
        configuration.setMapUnderscoreToCamelCase(true);

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
        resources.addAll(Arrays.asList(resolver.getResources(STARTER_LOG_XML)));
        resources.addAll(Arrays.asList(resolver.getResources(STARTER_SEGMENT_XML)));
        resources.addAll(Arrays.asList(resolver.getResources(EXT_XML)));
        return resources.toArray(new Resource[0]);
    }
}
