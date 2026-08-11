package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.richard.fyoung.customeradmin.sqlconfig.entity.SqlFieldTransform;
import com.richard.fyoung.customerwork.infra.sqlkit.FieldTransformer.FieldTransform;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;

/**
 * 结果列转换的 admin 侧薄壳：转换算法（DATE_FORMAT / VALUE_MAP）在 starter 的
 * {@link com.richard.fyoung.customerwork.infra.sqlkit.FieldTransformer}，本类只做两件与 admin 绑定的事——
 * 作为 Spring Bean 暴露给 {@link SqlQueryService}，以及把持久化实体 {@link SqlFieldTransform}
 * 转成 starter 的入参记录（让 starter 侧不依赖 admin 表结构）。
 * @author owlzhangfq@gmail.com
 */
@Component
public class FieldTransformer {

    private final com.richard.fyoung.customerwork.infra.sqlkit.FieldTransformer delegate =
        new com.richard.fyoung.customerwork.infra.sqlkit.FieldTransformer();

    /** 对结果行集应用全部转换器（原地修改行 Map）。 */
    public void apply(List<SqlFieldTransform> transforms, List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(transforms)) {
            return;
        }
        List<FieldTransform> configs = transforms.stream()
            .map(t -> new FieldTransform(t.getFieldName(), t.getTransformType(), t.getTransformConfig()))
            .toList();
        delegate.apply(configs, rows);
    }
}
