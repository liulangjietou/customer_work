package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库 FAQ Mapper（由 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>仅关键词召回因 {@code LIKE 多列 + 限额} 语义写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeMapper extends BaseMapper<KnowledgeDO> {

    /** 关键词召回：keyword/title/content 任一命中，取前 5 条（对应旧 RECALL_LIMIT）。 */
    List<KnowledgeDO> search(@Param("q") String q);
}
