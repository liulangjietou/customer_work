package com.richard.fyoung.customeradmin.ops.config;

import com.richard.fyoung.customeradmin.common.constant.StarterMapperXml;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customerwork.capability.csat.MybatisCsatStore;
import com.richard.fyoung.customerwork.capability.csat.mapper.CsatSurveyMapper;
import com.richard.fyoung.customerwork.capability.deadletter.MybatisDeadLetterStore;
import com.richard.fyoung.customerwork.capability.deadletter.mapper.DeadLetterMapper;
import com.richard.fyoung.customerwork.capability.knowledgegap.MybatisKnowledgeGapStore;
import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import com.richard.fyoung.customerwork.capability.prompt.MybatisPromptVersionStore;
import com.richard.fyoung.customerwork.capability.prompt.mapper.PromptVersionMapper;
import com.richard.fyoung.customerwork.capability.semanticcache.MybatisSemanticCacheStore;
import com.richard.fyoung.customerwork.capability.semanticcache.mapper.SemanticCacheMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成运营闭环门面。
 *
 * <p>五个域的 Mapper 都有自己的 XML，靠 namespace 绑定自动注册，
 * <b>不能</b>再登记进接口列表——同名语句会冲突。</p>
 * @author owlzhangfq@gmail.com
 */
final class OpsGatewayFactory {

    static final List<Class<?>> MAPPER_CLASSES = List.of();

    /** 末位的知识库 FAQ 是盲区"一键补知识"的落点，其余是运营看板自身的表。 */
    static final List<String> MAPPER_XML_LOCATIONS = List.of(
        StarterMapperXml.SEMANTIC_CACHE, StarterMapperXml.PROMPT_VERSION, StarterMapperXml.CSAT_SURVEY,
        StarterMapperXml.KNOWLEDGE_GAP, StarterMapperXml.DEAD_LETTER, StarterMapperXml.KNOWLEDGE);

    private OpsGatewayFactory() {
    }

    static OpsGateway build(CrossDbGateway gateway) {
        return new OpsGateway(
            new MybatisSemanticCacheStore(gateway.getMapper(SemanticCacheMapper.class)),
            new MybatisPromptVersionStore(gateway.getMapper(PromptVersionMapper.class)),
            new MybatisCsatStore(gateway.getMapper(CsatSurveyMapper.class)),
            new MybatisKnowledgeGapStore(gateway.getMapper(KnowledgeGapMapper.class)),
            new MybatisDeadLetterStore(gateway.getMapper(DeadLetterMapper.class)),
            gateway.getMapper(KnowledgeMapper.class));
    }
}
