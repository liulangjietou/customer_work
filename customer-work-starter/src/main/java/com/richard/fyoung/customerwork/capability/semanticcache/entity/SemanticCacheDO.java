package com.richard.fyoung.customerwork.capability.semanticcache.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 语义缓存持久化对象（贫血数据袋）：与 {@code cw_semantic_cache} 表一一映射。
 *
 * <p>领域快照见 {@link com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheEntry}。
 * {@code scopeId} 是<b>缓存分区键</b>（TenantResolver 由 sessionId 解析），与表上由拦截器自动改写的
 * {@code tenant_id} 是两回事：后者管"哪个租户能看到这行"，前者管"这条缓存属于哪个业务分区"，
 * 容量淘汰与失效清空都按前者进行，故必须显式落列。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_semantic_cache")
public class SemanticCacheDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String scopeId;
    /** 运行时配置 contentHash；旧请求只允许写回其发起时的代际。 */
    private String configGeneration;
    private String intent;

    /** 原始问题文本，排查"为什么这条命中了"时要看。 */
    private String question;

    /** 问题向量，逗号分隔的浮点数（与 admin 侧知识库向量列同一手法）。 */
    private String questionVector;

    private String answer;
    private Long hitCount;
    private Long createdAtMs;
    private Long lastHitAtMs;
}
