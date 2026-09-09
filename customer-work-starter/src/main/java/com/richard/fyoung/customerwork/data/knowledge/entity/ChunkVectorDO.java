package com.richard.fyoung.customerwork.data.knowledge.entity;

import lombok.Data;

/**
 * 检索打分阶段用的窄投影：<b>只有标识、分区与向量，不含正文</b>。
 *
 * <p>这是把检索从「40MB 一次性进内存」拉回来的关键一步。参与打分的可能是上万条分片，
 * 而最终只有 topN 的正文会被用到——此前那版实现把 {@code content} 一起 selectList 出来，
 * 其余全部白读。正文改由调用方在拿到 topN 之后按 id 回查。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Data
public class ChunkVectorDO {

    private Long id;

    private Long docRevisionId;

    private byte[] embedding;
}
