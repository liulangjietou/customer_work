package com.richard.fyoung.customerwork.data.knowledge.vector;

import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储装配：照本项目 Store SPI 的既有模式（接口 + 内存默认 + MyBatis 实现 + 可被覆盖）。
 *
 * <p>Mapper 拿不到时退回内存实现并记错误码——与长期记忆那边一致。区别在于：
 * 受管知识库那条链路<b>不接受这种降级</b>，{@code KnowledgeProvider#buildManaged} 会显式失败，
 * 因为降级的结果是回到 4 条演示文本，而运营会以为自己维护的知识库正在生效。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(ObjectProvider<KnowledgeChunkMapper> mapperProvider) {
        KnowledgeChunkMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.info("vector store: memory (持久化环境未激活；受管知识库需要 jdbc 才能工作)");
            return new InMemoryVectorStore();
        }
        log.info("vector store: jdbc (table=cw_knowledge_chunk, 定长向量 + 分批流式打分)");
        return new MybatisVectorStore(mapper);
    }
}
