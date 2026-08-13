package com.richard.fyoung.customeradmin.ops.jdbc;

import com.richard.fyoung.customerwork.capability.csat.CsatStore;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStore;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapStore;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersionStore;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheStore;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;

/**
 * 运营闭环（B6）各域在客服端库上的统一门面。
 *
 * <p><b>五个域共用一个门面与一个连接池</b>，而不是照配额/评测/badcase 那样各建一个：
 * 那几个是不同批次陆续加的、各自独立；而这五张表同属一批、都是低频运营查询
 * （运营一天看几次排行和列表），再开五个池只是白占连接数。</p>
 *
 * <p>持有的是 starter 的 Store 而非 admin 自己的 DAO：DO↔领域对象的转换、JSON 列解析、
 * 损坏行降级这些逻辑都在那边，后台再抄一份就多了一处会漂移的实现。</p>
 *
 * @param semanticCache   语义缓存：看缓存了什么、哪些真在被复用
 * @param promptVersion   提示词版本：归因时比对两版全文
 * @param csat            会话满意度：算 CSAT 与回收率
 * @param knowledgeGap    知识盲区：反复查不到的问题排行
 * @param deadLetter      死信队列：待重投与已放弃列表
 * @param knowledgeMapper 知识库 FAQ：盲区"一键补知识"的落点（与 badcase 转知识库同一张表）
 * @author owlzhangfq@gmail.com
 */
public record OpsGateway(
    SemanticCacheStore semanticCache,
    PromptVersionStore promptVersion,
    CsatStore csat,
    KnowledgeGapStore knowledgeGap,
    DeadLetterStore deadLetter,
    KnowledgeMapper knowledgeMapper
) {
}
