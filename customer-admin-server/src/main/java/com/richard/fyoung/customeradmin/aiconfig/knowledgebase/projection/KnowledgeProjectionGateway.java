package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeChunkMapper;
import com.richard.fyoung.customerwork.data.knowledge.mapper.KnowledgeVersionMapper;

/**
 * 知识投影的跨库门面：后台经它把知识写进客服端库。
 *
 * <p>方向是单向的——后台写、客服端读。让 starter 反向读后台库是不成立的方向，
 * 那正是这套知识栈此前割裂的根因。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeProjectionGateway(KnowledgeChunkMapper chunkMapper,
                                         KnowledgeVersionMapper versionMapper) {
}
