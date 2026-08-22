package com.richard.fyoung.customerwork.core.memory;

import java.util.List;

/**
 * 长期记忆存储 SPI（对应深度解析 3.4 的"多租户隔离 + 长期记忆"，三层记忆体系的 L2）。
 *
 * <p>按记忆分区键（{@code scopeId}，由 {@link MemorySubjectResolver} 从已验证主体解析）分区的事实存储，
 * 作为 {@link InMemoryLongTermMemory} 的共享底座，使同一分区的多个会话能共享长期记忆，
 * 而不同分区之间严格隔离（ToB 硬要求）。</p>
 *
 * <p>两种实现，由 {@link LongTermMemoryStoreConfig} 按 {@code customer-work.memory.store-mode} 选型：</p>
 * <ul>
 *   <li>{@code jdbc}（默认）：{@link MybatisLongTermMemoryStore}，落 {@code cw_long_term_memory} 表，
 *       重启与多副本部署下记忆不丢；</li>
 *   <li>{@code memory}：{@link InMemoryLongTermMemoryStore}，进程内，离线可测。</li>
 * </ul>
 *
 * <p>召回采用基于字符重合度的轻量打分（伪语义检索，见 {@link FactRelevanceScorer}）。生产中可替换为
 * 百炼长期记忆 / Mem0 / ReMe，或接入向量库做真正的语义召回——只要继续实现
 * {@code io.agentscope.core.memory.LongTermMemory} 接口即可，调用方无感知。</p>
 * @author owlzhangfq@gmail.com
 */
public interface LongTermMemoryStore {

    /** 记录一条事实。空白与重复（同分区内完全相同）不入库。 */
    void add(String scopeId, String fact);

    /**
     * 按与查询的相关度召回该分区的若干条记忆。
     *
     * @return 命中的记忆（按相关度降序），无任何相关项时返回空列表
     */
    List<String> recall(String scopeId, String query, int topK);

    /**
     * 按最新优先顺序列出分区记忆，供主体本人查看。
     *
     * <p>这是隐私治理阶段新增的可选能力。保留默认实现，避免已有的第三方存储实现仅因 SPI
     * 增加方法而在二进制链接或升级编译时被破坏；调用查看能力时仍以 fail-fast 明确暴露不支持。</p>
     */
    default List<String> list(String scopeId, int limit) {
        throw new UnsupportedOperationException("long-term memory listing is not supported");
    }

    /** 清空指定分区的全部记忆。 */
    void clear(String scopeId);

    /**
     * 隐私治理链路擦除指定主体分区。实现必须在删除失败时抛出，不能沿用增强能力的静默降级语义。
     */
    default void erase(String scopeId) {
        throw new UnsupportedOperationException("long-term memory erasure is not supported");
    }

    /** 指定分区已存储的记忆条数（便于观测 / 测试）。 */
    int size(String scopeId);
}
