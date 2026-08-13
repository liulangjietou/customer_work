package com.richard.fyoung.customeradmin.ops.config;

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

    private static final String XML_SEMANTIC_CACHE = "classpath*:customerwork/mapper/SemanticCacheMapper.xml";
    private static final String XML_PROMPT_VERSION = "classpath*:customerwork/mapper/PromptVersionMapper.xml";
    private static final String XML_CSAT = "classpath*:customerwork/mapper/CsatSurveyMapper.xml";
    private static final String XML_KNOWLEDGE_GAP = "classpath*:customerwork/mapper/KnowledgeGapMapper.xml";
    private static final String XML_DEAD_LETTER = "classpath*:customerwork/mapper/DeadLetterMapper.xml";
    /** 知识库 FAQ：盲区"一键补知识"的落点。 */
    private static final String XML_KNOWLEDGE = "classpath*:customerwork/mapper/KnowledgeMapper.xml";

    static final List<Class<?>> MAPPER_CLASSES = List.of();

    static final List<String> MAPPER_XML_LOCATIONS = List.of(
        XML_SEMANTIC_CACHE, XML_PROMPT_VERSION, XML_CSAT, XML_KNOWLEDGE_GAP, XML_DEAD_LETTER, XML_KNOWLEDGE);

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
