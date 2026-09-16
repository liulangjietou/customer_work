package com.richard.fyoung.customerwork.data.knowledge;

/** 单次授权查询得到的历史段落；列表查询不取正文，标题和版本来自投影保存的历史修订。 */
public record KnowledgePublicSource(long chunkId, String title, String knowledgeBase, Integer versionNo,
                                    String sourceVersion, String content) {
}
