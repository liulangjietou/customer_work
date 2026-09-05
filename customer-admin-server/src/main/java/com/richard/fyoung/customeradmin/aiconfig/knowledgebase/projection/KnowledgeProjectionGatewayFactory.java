package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.richard.fyoung.customeradmin.common.constant.StarterMapperXml;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;

import java.util.List;

/**
 * 知识投影门面的装配。
 *
 * @author owlzhangfq@gmail.com
 */
final class KnowledgeProjectionGatewayFactory {

    /** 只用 BaseMapper CRUD 的 Mapper 接口。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of(KnowledgeVersionMapper.class);

    /** 需加载的 starter Mapper XML。 */
    static final List<String> MAPPER_XML_LOCATIONS = List.of(StarterMapperXml.KNOWLEDGE_CHUNK);

    private KnowledgeProjectionGatewayFactory() {
    }

    static KnowledgeProjectionGateway build(CrossDbGateway gateway) {
        return new KnowledgeProjectionGateway(
            gateway.getMapper(KnowledgeChunkMapper.class),
            gateway.getMapper(KnowledgeVersionMapper.class));
    }
}
