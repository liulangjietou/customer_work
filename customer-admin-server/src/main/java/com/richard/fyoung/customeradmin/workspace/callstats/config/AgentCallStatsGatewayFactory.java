package com.richard.fyoung.customeradmin.workspace.callstats.config;

import com.richard.fyoung.customeradmin.common.constant.StarterMapperXml;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsExtMapper;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customerwork.data.calllog.AgentCallLogStore;
import com.richard.fyoung.customerwork.data.calllog.MybatisAgentCallLogStore;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.data.calllog.mapper.AgentCallSegmentMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateways;

import javax.sql.DataSource;
import java.util.List;

/**
 * 把某个数据源的 Mapper 环境装配成调用统计门面（{@link AgentCallStatsGateway}）。
 *
 * <p>两条装配路径：ADMIN 侧借用宿主主数据源（{@code attach}，池归容器管），APP 侧是客服端库的独立跨库池
 * （{@code create}，见 {@link AppAgentCallStatsGatewayProvider}）。建池/探测/专用 SqlSessionFactory 这套
 * 通用手法在 starter 的 {@code CrossDbGateways}，这里只声明"本域加载哪些 Mapper XML"：三张 Mapper 全部
 * 有 XML，靠 namespace 绑定完成接口注册与 MP BaseMapper CRUD 注入，无需再登记接口。</p>
 * @author owlzhangfq@gmail.com
 */
final class AgentCallStatsGatewayFactory {


    /** starter jar 内的调用日志主表 Mapper XML（classpath*: 才能命中 jar 内资源）。 */
    /** starter jar 内的调用分段 Mapper XML。 */
    /** admin 自带的读侧扩展 Mapper XML（放 /callstats 下，避开主 MP 默认的 /mapper 扫描）。 */
    private static final String EXT_XML = "classpath*:callstats/AgentCallStatsExtMapper.xml";

    /** 三张 Mapper 均由 XML namespace 绑定注册，无需接口登记。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of();

    /** 需加载的 Mapper XML 位置。 */
    static final List<String> MAPPER_XML_LOCATIONS =
        List.of(StarterMapperXml.AGENT_CALL_LOG, StarterMapperXml.AGENT_CALL_SEGMENT, EXT_XML);

    /** ADMIN 主数据源上的门面标识（借用宿主数据源，不自建池）。 */
    private static final String ADMIN_GATEWAY_NAME = "agent-call-stats-admin";

    private AgentCallStatsGatewayFactory() {
    }

    /**
     * 在宿主已有数据源（ADMIN 主库）上装配一套门面：连接池是宿主的，这里只另配一套 Mapper 环境。
     *
     * <p>启动期只解析 XML/建工厂，不连库。</p>
     */
    static AgentCallStatsGateway build(DataSource dataSource) {
        return build(CrossDbGateways.attach(dataSource, ADMIN_GATEWAY_NAME,
            MAPPER_CLASSES, MAPPER_XML_LOCATIONS));
    }

    /** 按跨库环境装配一套门面。 */
    static AgentCallStatsGateway build(CrossDbGateway gateway) {
        AgentCallLogMapper logMapper = gateway.getMapper(AgentCallLogMapper.class);
        AgentCallSegmentMapper segmentMapper = gateway.getMapper(AgentCallSegmentMapper.class);
        AgentCallStatsExtMapper extMapper = gateway.getMapper(AgentCallStatsExtMapper.class);
        AgentCallLogStore store = new MybatisAgentCallLogStore(logMapper, segmentMapper);
        return new AgentCallStatsGateway(extMapper, logMapper, segmentMapper, store);
    }
}
