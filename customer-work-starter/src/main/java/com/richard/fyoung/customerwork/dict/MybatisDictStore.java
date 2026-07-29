package com.richard.fyoung.customerwork.dict;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.dict.entity.DictItemEntity;
import com.richard.fyoung.customerwork.dict.entity.DictTypeEntity;
import com.richard.fyoung.customerwork.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.dict.mapper.DictTypeMapper;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-Plus 字典存储（生产实现：{@code customer-work.dict.store-mode=jdbc} 时装配）。
 *
 * <p>读 {@code cw_dict_type} / {@code cw_dict_item} 两表（客服端库唯一真源，后台管理系统直连维护）。
 * 字典是展示型数据、非核心链路：读取失败只记 error 并返回空列表，不阻断主流程（同 Feedback 先例）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisDictStore implements DictStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisDictStore.class);

    private final DictTypeMapper typeMapper;
    private final DictItemMapper itemMapper;

    public MybatisDictStore(DictTypeMapper typeMapper, DictItemMapper itemMapper) {
        this.typeMapper = typeMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public List<DictType> listEnabledTypes() {
        try {
            QueryWrapper<DictTypeEntity> wrapper = new QueryWrapper<DictTypeEntity>()
                .eq("enabled", true)
                .orderByAsc("dict_type");
            List<DictTypeEntity> rows = typeMapper.selectList(wrapper);
            List<DictType> result = new ArrayList<>(rows.size());
            for (DictTypeEntity row : rows) {
                result.add(new DictType(row.getDictType(), row.getTypeName(), row.getRemark(),
                    Boolean.TRUE.equals(row.getEnabled())));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisDictStore] listEnabledTypes failed, errorCode={}", "DICT-STORE-LISTTYPES-FAIL", e);
            return List.of();
        }
    }

    @Override
    public List<DictItem> findEnabledItems(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            return List.of();
        }
        try {
            QueryWrapper<DictItemEntity> wrapper = new QueryWrapper<DictItemEntity>()
                .eq("dict_type", dictType)
                .eq("enabled", true)
                .orderByAsc("sort", "id");
            List<DictItemEntity> rows = itemMapper.selectList(wrapper);
            List<DictItem> result = new ArrayList<>(rows.size());
            for (DictItemEntity row : rows) {
                result.add(new DictItem(row.getId(), row.getDictType(), row.getItemKey(), row.getItemLabel(),
                    row.getSort() == null ? 0 : row.getSort(), Boolean.TRUE.equals(row.getEnabled()), row.getRemark()));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisDictStore] findEnabledItems failed, errorCode={}, dictType={}",
                "DICT-STORE-FINDITEMS-FAIL", dictType, e);
            return List.of();
        }
    }
}
