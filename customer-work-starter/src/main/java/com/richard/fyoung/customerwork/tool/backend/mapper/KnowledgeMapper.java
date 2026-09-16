package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
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

    /** 冻结当前受信租户的 FAQ；二进制租户条件始终有效，不依赖租户插件开关。 */
    @InterceptorIgnore(tenantLine = "true")
    List<KnowledgeDO> snapshotForTenant(@Param("tenantId") String tenantId);

    /** 仅从调用方已冻结的 JSON 数据检索，不访问正式表，也不产生知识缺口统计。 */
    @InterceptorIgnore(tenantLine = "true")
    List<KnowledgeDO> searchSnapshot(@Param("q") String q, @Param("snapshotJson") String snapshotJson);
}
