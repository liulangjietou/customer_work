package com.richard.fyoung.customerwork.data.knowledge.vector;

import java.util.List;

/**
 * 向量检索 SPI。
 *
 * <p><b>为什么要这层抽象</b>：此前受管知识库的检索是「把该版本下全部已授权 chunk 一次性
 * {@code selectList} 进内存 → 逐条 JSON 解析向量 → Java 里逐条算余弦 → 内存排序 → 取 topN」，
 * 而且连 {@code content} 正文一起拉了出来。按 1024 维、1 万 chunk 估算，单次提问要从 MySQL
 * 读出并解析约 40MB 数据、做 1000 万次浮点乘加，全部发生在请求线程上。今天它只打在后台工作台
 * （低频、内部员工）所以没炸；一旦 C 端知识链路打通，同一段代码就会挂到每个用户的每一轮对话上。</p>
 *
 * <p>把检索收敛到这个接口后，实现可以逐步演进而调用方不动：
 * 先用 MySQL 实现（定长向量列 + 只读向量不读正文 + 分批流式打分），
 * 规模上来后替换为专用向量库（本机 kb-rag 栈已有 Qdrant 实例），调用方无感。</p>
 *
 * <p><b>ACL 不在这一层</b>：谁能看哪些分区是业务语义，由调用方在构造
 * {@link VectorQuery#partitions()} 时决定，并在拿到结果后按 {@link VectorMatch#partition()}
 * 再复核一次。SPI 只负责"在给定分区里找最像的"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public interface VectorStore {

    /**
     * 在给定分区内检索最相近的若干条。
     *
     * <p>分区集合为空时必须返回空结果——权限过滤后无可查分区，语义是"查不到"，
     * 落成"全量检索"就是越权。</p>
     */
    List<VectorMatch> search(VectorQuery query);
}
