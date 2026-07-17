package com.richard.fyoung.customeradmin.workspace.knowledge.embedding;

import java.util.List;

/**
 * Embedding 向量客户端（P3-2）。抽象成接口以便单测 mock 隔离真实 Embedding API，
 * 生产实现为 {@link DashScopeEmbeddingClient}（直连 DashScope text-embedding REST）。
 * @author owlzhangfq@gmail.com
 */
public interface EmbeddingClient {

    /** 批量把文档文本转成向量（顺序与入参一致）。text_type=document。 */
    List<float[]> embedDocuments(List<String> texts);

    /** 把查询文本转成向量。text_type=query。 */
    float[] embedQuery(String text);

    /** 当前配置的向量维度。 */
    int dimensions();

    /** 当前配置的 Embedding 模型名。 */
    String modelName();
}
