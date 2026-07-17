package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.ProductDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>单条查询走 {@link BaseMapper}；仅关键词推荐因 {@code LIKE + 限额} 语义写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ProductMapper extends BaseMapper<ProductDO> {

    /** 关键词推荐：在售商品按名称/品类模糊匹配，库存倒序取前 3（对应旧 RECOMMEND_LIMIT）。 */
    List<ProductDO> recommend(@Param("keyword") String keyword);
}
