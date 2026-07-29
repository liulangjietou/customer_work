package com.richard.fyoung.customeradmin.dict.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.dict.config.DictGatewayProvider;
import com.richard.fyoung.customeradmin.dict.dto.DictItemSaveRequest;
import com.richard.fyoung.customeradmin.dict.dto.DictOptionVO;
import com.richard.fyoung.customeradmin.dict.dto.DictTypeSaveRequest;
import com.richard.fyoung.customeradmin.dict.jdbc.DictGateway;
import com.richard.fyoung.customerwork.dict.entity.DictItemEntity;
import com.richard.fyoung.customerwork.dict.entity.DictTypeEntity;
import com.richard.fyoung.customerwork.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.dict.mapper.DictTypeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DictService} 单测：类型编码判重/不可变更、有项类型禁删、字典项同类型判重、
 * 停用类型的 options 返回空、缺参 fast fail。
 * @author owlzhangfq@gmail.com
 */
class DictServiceTest {

    private DictTypeMapper typeMapper;
    private DictItemMapper itemMapper;
    private DictService service;

    @BeforeEach
    void setUp() {
        typeMapper = mock(DictTypeMapper.class);
        itemMapper = mock(DictItemMapper.class);
        DictGatewayProvider provider = mock(DictGatewayProvider.class);
        when(provider.get()).thenReturn(new DictGateway(typeMapper, itemMapper));
        service = new DictService(provider);
    }

    private DictTypeSaveRequest typeRequest(String code, String name) {
        DictTypeSaveRequest request = new DictTypeSaveRequest();
        request.setDictType(code);
        request.setTypeName(name);
        return request;
    }

    private DictItemSaveRequest itemRequest(String key, String label) {
        DictItemSaveRequest request = new DictItemSaveRequest();
        request.setItemKey(key);
        request.setItemLabel(label);
        return request;
    }

    private DictTypeEntity typeRow(long id, String code, boolean enabled) {
        DictTypeEntity row = new DictTypeEntity();
        row.setId(id);
        row.setDictType(code);
        row.setTypeName("名称");
        row.setEnabled(enabled);
        return row;
    }

    @Test
    void createType_shouldRejectDuplicateCode() {
        when(typeMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> service.createType(typeRequest("order_status", "订单状态")));
        assertEquals(ResultCode.RESOURCE_DUPLICATE, e.getResultCode());
        verify(typeMapper, never()).insert(any(DictTypeEntity.class));
    }

    @Test
    void createType_shouldDefaultEnabled_andStampTimestamps() {
        when(typeMapper.selectCount(any())).thenReturn(0L);

        service.createType(typeRequest("order_status", "订单状态"));

        ArgumentCaptor<DictTypeEntity> captor = ArgumentCaptor.forClass(DictTypeEntity.class);
        verify(typeMapper).insert(captor.capture());
        assertTrue(captor.getValue().getEnabled(), "缺省应启用");
        assertTrue(captor.getValue().getCreatedAtMs() > 0);
    }

    @Test
    void updateType_shouldRejectCodeChange() {
        when(typeMapper.selectById(1L)).thenReturn(typeRow(1L, "order_status", true));

        BizException e = assertThrows(BizException.class, () -> service.updateType(1L, typeRequest("other_code", "订单状态")));
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    @Test
    void deleteType_shouldRejectWhenItemsExist() {
        when(typeMapper.selectById(1L)).thenReturn(typeRow(1L, "order_status", true));
        when(itemMapper.selectCount(any())).thenReturn(3L);

        BizException e = assertThrows(BizException.class, () -> service.deleteType(1L));
        assertEquals(ResultCode.RESOURCE_IN_USE, e.getResultCode());
        verify(typeMapper, never()).deleteById(1L);
    }

    @Test
    void createItem_shouldRejectDuplicateKeyInSameType() {
        // 第一次 selectCount 校验类型存在，第二次校验键唯一
        when(typeMapper.selectCount(any())).thenReturn(1L);
        when(itemMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> service.createItem("order_status", itemRequest("待支付", "待支付")));
        assertEquals(ResultCode.RESOURCE_DUPLICATE, e.getResultCode());
        verify(itemMapper, never()).insert(any(DictItemEntity.class));
    }

    @Test
    void createItem_shouldRejectUnknownType() {
        when(typeMapper.selectCount(any())).thenReturn(0L);

        BizException e = assertThrows(BizException.class, () -> service.createItem("no_such", itemRequest("k", "v")));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, e.getResultCode());
    }

    @Test
    void options_shouldReturnEmpty_whenTypeDisabledOrMissing() {
        when(typeMapper.selectCount(any())).thenReturn(0L);

        assertTrue(service.options("order_status").isEmpty(), "停用/不存在的类型应返回空列表");
        verify(itemMapper, never()).selectList(any());
    }

    @Test
    void options_shouldReturnEnabledItems_asValueLabelPairs() {
        when(typeMapper.selectCount(any())).thenReturn(1L);
        DictItemEntity item = new DictItemEntity();
        item.setDictType("order_status");
        item.setItemKey("待支付");
        item.setItemLabel("待支付");
        item.setSort(1);
        item.setEnabled(true);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        List<DictOptionVO> options = service.options("order_status");
        assertEquals(1, options.size());
        assertEquals("待支付", options.get(0).getValue());
        assertEquals("待支付", options.get(0).getLabel());
    }

    @Test
    void listItems_shouldFastFailOnBlankType() {
        BizException e = assertThrows(BizException.class, () -> service.listItems("  "));
        assertEquals(ResultCode.PARAM_MISSING, e.getResultCode());
    }
}
