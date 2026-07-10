package com.richard.fyoung.customeradmin.system.permission.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuReorderRequest;
import com.richard.fyoung.customeradmin.system.menu.service.MenuChangeLogService;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionSaveRequest;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PermissionService} 单测：菜单管理"改动即时生效、发布=广播、变更留痕"这套模型的核心行为。
 * @author owlzhangfq@gmail.com
 */
class PermissionServiceTest {

    private SysPermissionMapper mapper;
    private MenuChangeLogService changeLogService;
    private MenuVersionHolder menuVersionHolder;
    private PermissionService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SysPermission.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(SysPermissionMapper.class);
        changeLogService = mock(MenuChangeLogService.class);
        menuVersionHolder = mock(MenuVersionHolder.class);
        service = new PermissionService(mapper, changeLogService, menuVersionHolder);
    }

    @Test
    void tree_shouldCarryIconType_notJustIcon() {
        // 回归用例：曾经漏改 toVo()，导致 /tree 接口返回的 iconType 永远是 null（哪怕 icon 是一个
        // 上传图片 URL），前端拿不到 iconType 就会误判成图标库名字去 <component :is> 动态渲染，
        // 直接把整棵菜单树渲染崩溃。
        SysPermission node = new SysPermission();
        node.setId(10L);
        node.setParentId(1L);
        node.setPermName("用户管理");
        node.setPermCode("user");
        node.setType(1);
        node.setIcon("/api/menu-icons/f58020a1.jpeg");
        node.setIconType("image");
        node.setSort(1);
        when(mapper.selectList(null)).thenReturn(List.of(node));

        List<com.richard.fyoung.customeradmin.system.permission.dto.PermissionVO> result = service.tree();

        assertEquals("image", result.get(0).getIconType());
        assertEquals("/api/menu-icons/f58020a1.jpeg", result.get(0).getIcon());
    }

    @Test
    void create_shouldRecordCreateLog_withNoBeforeSnapshot() {
        when(mapper.exists(any())).thenReturn(false);
        PermissionSaveRequest request =
            new PermissionSaveRequest(1L, "菜单管理", "menu", 1, "/system/menu", "Menu", "library", 4);

        service.create(request);

        ArgumentCaptor<SysPermission> afterCaptor = ArgumentCaptor.forClass(SysPermission.class);
        verify(changeLogService).record(any(), eq("CREATE"), isNull(), afterCaptor.capture());
        assertEquals("menu", afterCaptor.getValue().getPermCode());
        assertEquals("library", afterCaptor.getValue().getIconType());
    }

    @Test
    void create_shouldReject_whenPermCodeAlreadyExists() {
        when(mapper.exists(any())).thenReturn(true);
        PermissionSaveRequest request =
            new PermissionSaveRequest(1L, "菜单管理", "menu", 1, "/system/menu", "Menu", "library", 4);

        assertThrows(BizException.class, () -> service.create(request));
    }

    @Test
    void update_shouldPreserveAuditFields_notOverwriteWithNull() {
        SysPermission existing = new SysPermission();
        existing.setId(13L);
        existing.setParentId(1L);
        existing.setPermCode("menu");
        existing.setCreateBy(1L);
        when(mapper.selectById(13L)).thenReturn(existing);
        PermissionSaveRequest request =
            new PermissionSaveRequest(1L, "菜单管理v2", "menu", 1, "/system/menu", "Menu", "library", 5);

        service.update(13L, request);

        ArgumentCaptor<SysPermission> afterCaptor = ArgumentCaptor.forClass(SysPermission.class);
        verify(mapper).updateById(afterCaptor.capture());
        // copyOf() 只拷业务字段，createBy/createTime 留空交给 MyBatis-Plus 默认 NOT_NULL 更新策略
        // 自动跳过（不覆盖库里已有值），这里断言确实留空而不是被错误地设成了别的值。
        assertNull(afterCaptor.getValue().getCreateBy());
        assertEquals("菜单管理v2", afterCaptor.getValue().getPermName());
        verify(changeLogService).record(eq(13L), eq("UPDATE"), eq(existing), any());
    }

    @Test
    void delete_shouldReject_whenHasChildren() {
        SysPermission existing = new SysPermission();
        existing.setId(1L);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.exists(any())).thenReturn(true);

        assertThrows(BizException.class, () -> service.delete(1L));
        verify(changeLogService, never()).record(any(), eq("DELETE"), any(), any());
    }

    @Test
    void delete_shouldRecordDeleteLog_withNoAfterSnapshot() {
        SysPermission existing = new SysPermission();
        existing.setId(20L);
        when(mapper.selectById(20L)).thenReturn(existing);
        when(mapper.exists(any())).thenReturn(false);

        service.delete(20L);

        verify(changeLogService).record(eq(20L), eq("DELETE"), eq(existing), isNull());
    }

    @Test
    void reorder_shouldRecordMoveLog_onlyWhenParentChanges() {
        SysPermission sameParent = new SysPermission();
        sameParent.setId(1L);
        sameParent.setParentId(2L);
        SysPermission movedNode = new SysPermission();
        movedNode.setId(2L);
        movedNode.setParentId(2L);
        when(mapper.selectById(1L)).thenReturn(sameParent);
        when(mapper.selectById(2L)).thenReturn(movedNode);

        service.reorder(new MenuReorderRequest(List.of(
            new MenuReorderRequest.Item(1L, 2L, 1),   // 同父，只是调顺序
            new MenuReorderRequest.Item(2L, 9L, 1))));  // 换了父节点

        verify(changeLogService, never()).record(eq(1L), eq("MOVE"), any(), any());
        verify(changeLogService).record(eq(2L), eq("MOVE"), any(), any());
        verify(mapper, times(2)).updateById(any(SysPermission.class));
    }

    @Test
    void publish_shouldBumpMenuVersion() {
        service.publish();

        verify(menuVersionHolder).bump();
    }
}
