package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.config;

import com.richard.fyoung.customeradmin.common.constant.StarterMapperXml;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapService;
import com.richard.fyoung.customerwork.capability.knowledgegap.MybatisKnowledgeGapStore;
import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import com.richard.fyoung.customerwork.core.support.OpsScopeResolver;
import com.richard.fyoung.customerwork.infra.config.properties.KnowledgeGapProperties;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成知识盲区埋点服务。
 *
 * <p>门面类型直接用 starter 的 {@link KnowledgeGapService}：什么问题算盲区（长度下限、
 * 空白过滤）、按哪个维度分区（{@link OpsScopeResolver} 取租户而非用户）、
 * 计数怎么 upsert，规则全在那里。admin 抄一份就多了一处会漂移的实现——
 * 两边对同一份数据算出不同结果是最难查的 bug。</p>
 *
 * <p>盲区表 {@code cw_knowledge_gap} 在客服端库，与工具路径写入的是同一张表；
 * 两条 RAG 路径的埋点必须落在同一处，看板才是完整的。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class KnowledgeGapGatewayFactory {

    /** 该 Mapper 有 XML，靠 namespace 自动绑定，不能再登记进接口列表（同名语句会冲突）。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of();

    static final List<String> MAPPER_XML_LOCATIONS = List.of(StarterMapperXml.KNOWLEDGE_GAP);

    private KnowledgeGapGatewayFactory() {
    }

    static KnowledgeGapService build(CrossDbGateway gateway) {
        KnowledgeGapProperties properties = new KnowledgeGapProperties();
        // 走到这里说明后台已决定要埋点，属性对象只承载判定阈值，enabled 恒真；
        // 是否埋点由 AdminKnowledgeGapRecorder 那一层的开关决定，不在这里二次判断。
        properties.setEnabled(true);
        return new KnowledgeGapService(
            new MybatisKnowledgeGapStore(gateway.getMapper(KnowledgeGapMapper.class)),
            new OpsScopeResolver(),
            properties);
    }
}
