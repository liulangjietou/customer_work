package com.richard.fyoung.customeradmin.dict.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.dict.config.DictGatewayProvider;
import com.richard.fyoung.customeradmin.dict.dto.DictItemSaveRequest;
import com.richard.fyoung.customeradmin.dict.dto.DictItemVO;
import com.richard.fyoung.customeradmin.dict.dto.DictOptionVO;
import com.richard.fyoung.customeradmin.dict.dto.DictTypeSaveRequest;
import com.richard.fyoung.customeradmin.dict.dto.DictTypeVO;
import com.richard.fyoung.customeradmin.dict.jdbc.DictGateway;
import com.richard.fyoung.customerwork.data.dict.entity.DictItemEntity;
import com.richard.fyoung.customerwork.data.dict.entity.DictTypeEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 字典管理：类型 + 字典项两级 CRUD，及消费端下拉选项查询。
 *
 * <p><b>全量取回、不做分页 SQL，是刻意的</b>：字典本来就是"就几条数据、不值当建表"的场景，
 * 单类型几条到几十条、类型总数也就几十个；真涨到需要分页的量级，说明这数据不该进字典。</p>
 *
 * <p>写的是客服端库 {@code cw_dict_type} / {@code cw_dict_item}（单一数据真源），客服端
 * {@code DictStore}（store-mode=jdbc）读同两张表，改动即时可见，无缓存同步问题。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DictService {

    private final DictGatewayProvider gatewayProvider;

    public DictService(DictGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    // ---------- 字典类型 ----------

    /** 全部字典类型（含停用，编码升序），附各类型的字典项数量。 */
    public List<DictTypeVO> listTypes() {
        DictGateway gateway = gatewayProvider.get();
        List<DictTypeEntity> rows = gateway.typeMapper()
            .selectList(new QueryWrapper<DictTypeEntity>().orderByAsc("dict_type"));
        List<DictTypeVO> result = new ArrayList<>(rows.size());
        for (DictTypeEntity row : rows) {
            DictTypeVO vo = toTypeVO(row);
            vo.setItemCount(gateway.itemMapper().selectCount(
                new QueryWrapper<DictItemEntity>().eq("dict_type", row.getDictType())));
            result.add(vo);
        }
        return result;
    }

    /** 新增类型；编码唯一冲突抛 {@link ResultCode#RESOURCE_DUPLICATE}。 */
    public void createType(DictTypeSaveRequest request) {
        DictGateway gateway = gatewayProvider.get();
        Long exists = gateway.typeMapper().selectCount(
            new QueryWrapper<DictTypeEntity>().eq("dict_type", request.getDictType()));
        if (exists != null && exists > 0) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "字典类型已存在: " + request.getDictType());
        }
        long now = System.currentTimeMillis();
        DictTypeEntity row = new DictTypeEntity();
        row.setDictType(request.getDictType());
        row.setTypeName(request.getTypeName());
        row.setRemark(request.getRemark());
        row.setEnabled(request.getEnabled() == null || request.getEnabled());
        row.setCreatedAtMs(now);
        row.setUpdatedAtMs(now);
        gateway.typeMapper().insert(row);
    }

    /** 编辑类型（名称/备注/启停）；类型编码不允许变更。 */
    public void updateType(Long id, DictTypeSaveRequest request) {
        DictGateway gateway = gatewayProvider.get();
        DictTypeEntity row = requireType(gateway, id);
        if (!row.getDictType().equals(request.getDictType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "字典类型编码不允许变更: " + row.getDictType());
        }
        row.setTypeName(request.getTypeName());
        row.setRemark(request.getRemark());
        if (request.getEnabled() != null) {
            row.setEnabled(request.getEnabled());
        }
        row.setUpdatedAtMs(System.currentTimeMillis());
        gateway.typeMapper().updateById(row);
    }

    /** 删除类型；仍有字典项时拒绝（先清空项，避免留下孤儿项）。 */
    public void deleteType(Long id) {
        DictGateway gateway = gatewayProvider.get();
        DictTypeEntity row = requireType(gateway, id);
        Long itemCount = gateway.itemMapper().selectCount(
            new QueryWrapper<DictItemEntity>().eq("dict_type", row.getDictType()));
        if (itemCount != null && itemCount > 0) {
            throw new BizException(ResultCode.RESOURCE_IN_USE,
                "该类型下仍有 " + itemCount + " 个字典项，请先删除字典项");
        }
        gateway.typeMapper().deleteById(id);
    }

    // ---------- 字典项 ----------

    /** 某类型下全部字典项（含停用，sort 升序）。 */
    public List<DictItemVO> listItems(String dictType) {
        requireText(dictType);
        List<DictItemEntity> rows = gatewayProvider.get().itemMapper().selectList(
            new QueryWrapper<DictItemEntity>().eq("dict_type", dictType).orderByAsc("sort", "id"));
        List<DictItemVO> result = new ArrayList<>(rows.size());
        for (DictItemEntity row : rows) {
            result.add(toItemVO(row));
        }
        return result;
    }

    /** 新增字典项；同类型下键唯一冲突抛 {@link ResultCode#RESOURCE_DUPLICATE}。 */
    public void createItem(String dictType, DictItemSaveRequest request) {
        requireText(dictType);
        DictGateway gateway = gatewayProvider.get();
        requireTypeByCode(gateway, dictType);
        Long exists = gateway.itemMapper().selectCount(new QueryWrapper<DictItemEntity>()
            .eq("dict_type", dictType).eq("item_key", request.getItemKey()));
        if (exists != null && exists > 0) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "字典项已存在: " + request.getItemKey());
        }
        long now = System.currentTimeMillis();
        DictItemEntity row = new DictItemEntity();
        row.setDictType(dictType);
        row.setItemKey(request.getItemKey());
        row.setItemLabel(request.getItemLabel());
        row.setSort(request.getSort() == null ? 0 : request.getSort());
        row.setEnabled(request.getEnabled() == null || request.getEnabled());
        row.setRemark(request.getRemark());
        row.setCreatedAtMs(now);
        row.setUpdatedAtMs(now);
        gateway.itemMapper().insert(row);
    }

    /** 编辑字典项（键/文案/排序/启停/备注）；不允许挪类型。改键时校验同类型下唯一。 */
    public void updateItem(Long id, DictItemSaveRequest request) {
        DictGateway gateway = gatewayProvider.get();
        DictItemEntity row = requireItem(gateway, id);
        if (!row.getItemKey().equals(request.getItemKey())) {
            Long exists = gateway.itemMapper().selectCount(new QueryWrapper<DictItemEntity>()
                .eq("dict_type", row.getDictType()).eq("item_key", request.getItemKey()));
            if (exists != null && exists > 0) {
                throw new BizException(ResultCode.RESOURCE_DUPLICATE, "字典项已存在: " + request.getItemKey());
            }
        }
        row.setItemKey(request.getItemKey());
        row.setItemLabel(request.getItemLabel());
        if (request.getSort() != null) {
            row.setSort(request.getSort());
        }
        if (request.getEnabled() != null) {
            row.setEnabled(request.getEnabled());
        }
        row.setRemark(request.getRemark());
        row.setUpdatedAtMs(System.currentTimeMillis());
        gateway.itemMapper().updateById(row);
    }

    /** 删除字典项。 */
    public void deleteItem(Long id) {
        DictGateway gateway = gatewayProvider.get();
        requireItem(gateway, id);
        gateway.itemMapper().deleteById(id);
    }

    // ---------- 消费端 ----------

    /** 某类型下启用项的下拉选项（sort 升序）；类型停用或不存在返回空列表（消费端按无字典配置降级）。 */
    public List<DictOptionVO> options(String dictType) {
        requireText(dictType);
        DictGateway gateway = gatewayProvider.get();
        Long enabledType = gateway.typeMapper().selectCount(new QueryWrapper<DictTypeEntity>()
            .eq("dict_type", dictType).eq("enabled", true));
        if (enabledType == null || enabledType == 0) {
            return List.of();
        }
        List<DictItemEntity> rows = gateway.itemMapper().selectList(new QueryWrapper<DictItemEntity>()
            .eq("dict_type", dictType).eq("enabled", true).orderByAsc("sort", "id"));
        List<DictOptionVO> result = new ArrayList<>(rows.size());
        for (DictItemEntity row : rows) {
            result.add(new DictOptionVO(row.getItemKey(), row.getItemLabel()));
        }
        return result;
    }

    // ---------- 私有辅助 ----------

    private DictTypeEntity requireType(DictGateway gateway, Long id) {
        DictTypeEntity row = gateway.typeMapper().selectById(id);
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "字典类型不存在: " + id);
        }
        return row;
    }

    private void requireTypeByCode(DictGateway gateway, String dictType) {
        Long exists = gateway.typeMapper().selectCount(
            new QueryWrapper<DictTypeEntity>().eq("dict_type", dictType));
        if (exists == null || exists == 0) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "字典类型不存在: " + dictType);
        }
    }

    private DictItemEntity requireItem(DictGateway gateway, Long id) {
        DictItemEntity row = gateway.itemMapper().selectById(id);
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "字典项不存在: " + id);
        }
        return row;
    }

    private void requireText(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少字典类型编码");
        }
    }

    private DictTypeVO toTypeVO(DictTypeEntity row) {
        DictTypeVO vo = new DictTypeVO();
        vo.setId(row.getId());
        vo.setDictType(row.getDictType());
        vo.setTypeName(row.getTypeName());
        vo.setRemark(row.getRemark());
        vo.setEnabled(row.getEnabled());
        vo.setCreatedAtMs(row.getCreatedAtMs());
        vo.setUpdatedAtMs(row.getUpdatedAtMs());
        return vo;
    }

    private DictItemVO toItemVO(DictItemEntity row) {
        DictItemVO vo = new DictItemVO();
        vo.setId(row.getId());
        vo.setDictType(row.getDictType());
        vo.setItemKey(row.getItemKey());
        vo.setItemLabel(row.getItemLabel());
        vo.setSort(row.getSort());
        vo.setEnabled(row.getEnabled());
        vo.setRemark(row.getRemark());
        vo.setCreatedAtMs(row.getCreatedAtMs());
        vo.setUpdatedAtMs(row.getUpdatedAtMs());
        return vo;
    }
}
