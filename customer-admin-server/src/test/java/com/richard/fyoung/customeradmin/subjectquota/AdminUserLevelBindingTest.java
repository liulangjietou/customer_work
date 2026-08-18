package com.richard.fyoung.customeradmin.subjectquota;

import com.richard.fyoung.customeradmin.subjectquota.runtime.AdminUserLevelBinding;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台用户等级绑定单测：读到绑定、空绑定当未绑定、非数字 ID 与查询异常都按未绑定处理。
 * @author owlzhangfq@gmail.com
 */
class AdminUserLevelBindingTest {

    private final SysUserMapper mapper = mock(SysUserMapper.class);
    private final AdminUserLevelBinding binding = new AdminUserLevelBinding(mapper);

    private static SysUser userWithLevel(String levelCode) {
        SysUser user = new SysUser();
        user.setLevelCode(levelCode);
        return user;
    }

    @Test
    void levelCodeOf_shouldReturnBoundLevel() {
        when(mapper.selectOne(any())).thenReturn(userWithLevel("admin-power"));
        assertEquals(Optional.of("admin-power"), binding.levelCodeOf("42"));
    }

    @Test
    void levelCodeOf_shouldTreatBlankAsUnbound() {
        when(mapper.selectOne(any())).thenReturn(userWithLevel("  "));
        assertTrue(binding.levelCodeOf("42").isEmpty(), "空白等级等于没绑定，应走默认档");
    }

    @Test
    void levelCodeOf_shouldReturnEmpty_whenUserMissing() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(binding.levelCodeOf("42").isEmpty());
    }

    @Test
    void levelCodeOf_shouldNotQuery_forBlankId() {
        assertTrue(binding.levelCodeOf("  ").isEmpty());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void levelCodeOf_shouldReturnEmpty_forNonNumericId() {
        // 登录 ID 恒为 sys_user.id（Long），解析不了说明调用方传错了主体
        assertTrue(binding.levelCodeOf("not-a-number").isEmpty());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void levelCodeOf_shouldReturnEmpty_whenQueryFails() {
        when(mapper.selectOne(any())).thenThrow(new IllegalStateException("db down"));
        // 为一次等级查询失败把用户挡在门外，比放行的代价大得多
        assertTrue(binding.levelCodeOf("42").isEmpty(), "查询异常应按未绑定处理而不是抛出");
    }
}
