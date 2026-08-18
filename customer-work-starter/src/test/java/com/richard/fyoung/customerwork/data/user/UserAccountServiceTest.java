package com.richard.fyoung.customerwork.data.user;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户账户服务单测（内存存储）：注册成功/重名 fast-fail、BCrypt 校验、密码错/停用统一返回 empty。
 * @author owlzhangfq@gmail.com
 */
class UserAccountServiceTest {

    private InMemoryUserAccountStore store;
    private UserAccountService service;

    private void init() {
        store = new InMemoryUserAccountStore();
        service = new UserAccountService(store);
    }

    @Test
    void register_shouldHashPasswordAndPersist() {
        init();
        UserAccount account = service.register("alice", "secret123", "爱丽丝", "13800000000");
        assertTrue(account.getId().startsWith("U-"));
        assertEquals("alice", account.getUsername());
        assertNotEquals("secret123", account.getPasswordHash(), "密码必须哈希存储，不得明文");
        assertTrue(account.isActive());
    }

    @Test
    void register_duplicateUsername_shouldFastFail() {
        init();
        service.register("bob", "pw", "鲍勃", null);
        assertThrows(IllegalStateException.class, () -> service.register("bob", "pw2", "鲍勃2", null));
    }

    @Test
    void verifyLogin_correctPassword_shouldReturnAccount() {
        init();
        service.register("carol", "correct-pw", "卡罗尔", null);
        Optional<UserAccount> logged = service.verifyLogin("carol", "correct-pw");
        assertTrue(logged.isPresent());
        assertEquals("carol", logged.get().getUsername());
    }

    @Test
    void verifyLogin_wrongPassword_shouldReturnEmpty() {
        init();
        service.register("dave", "right-pw", "戴夫", null);
        assertTrue(service.verifyLogin("dave", "wrong-pw").isEmpty());
    }

    @Test
    void verifyLogin_unknownUser_shouldReturnEmpty() {
        init();
        assertTrue(service.verifyLogin("ghost", "any").isEmpty());
    }

    @Test
    void verifyLogin_disabledAccount_shouldReturnEmpty() {
        init();
        UserAccount account = service.register("erin", "pw", "艾琳", null);
        account.disable();
        store.save(account);
        assertTrue(service.verifyLogin("erin", "pw").isEmpty(), "停用账户即使密码正确也不得登录");
    }

    @Test
    void findById_shouldReturnRegisteredAccount() {
        init();
        UserAccount account = service.register("frank", "pw", "弗兰克", null);
        assertTrue(service.findById(account.getId()).isPresent());
    }

    @Test
    void changeAvatar_shouldUpdateEntityField() {
        UserAccount account = UserAccount.create("U-x", "grace", "hash", "格蕾丝", null);
        assertNull(account.getAvatarUrl(), "注册时无头像");
        account.changeAvatar("/api/avatars/abc.png");
        assertEquals("/api/avatars/abc.png", account.getAvatarUrl());
    }

    @Test
    void updateAvatar_shouldPersistAndReturnAccount() {
        init();
        UserAccount account = service.register("henry", "pw", "亨利", null);
        UserAccount updated = service.updateAvatar(account.getId(), "/api/avatars/henry.png");
        assertEquals("/api/avatars/henry.png", updated.getAvatarUrl());
        assertEquals("/api/avatars/henry.png",
            service.findById(account.getId()).orElseThrow().getAvatarUrl(), "头像必须落存储");
    }

    @Test
    void updateAvatar_unknownUser_shouldFastFail() {
        init();
        assertThrows(IllegalStateException.class, () -> service.updateAvatar("U-ghost", "/api/avatars/x.png"));
    }

    @Test
    void register_shouldApplyDefaultLevel() {
        UserAccountService withLevel = new UserAccountService(new InMemoryUserAccountStore(), "free");
        UserAccount account = withLevel.register("levon", "pwd12345", "利文", null);
        assertEquals("free", account.getLevelCode(), "注册即落默认档，运营才能在后台看到这个人现在是哪一档");
    }

    @Test
    void updateLevel_shouldChangeAndPersist() {
        InMemoryUserAccountStore store = new InMemoryUserAccountStore();
        UserAccountService svc = new UserAccountService(store, "free");
        UserAccount created = svc.register("mia", "pwd12345", "米娅", null);

        svc.updateLevel(created.getId(), "vip");
        assertEquals("vip", store.findById(created.getId()).orElseThrow().getLevelCode());

        svc.updateLevel(created.getId(), null);
        assertNull(store.findById(created.getId()).orElseThrow().getLevelCode(),
            "传 null 表示取消特批、回到默认档，必须真的写空");
    }

    @Test
    void updateLevel_shouldNotifyListener() {
        InMemoryUserAccountStore store = new InMemoryUserAccountStore();
        java.util.List<String> notified = new java.util.ArrayList<>();
        UserAccountService svc = new UserAccountService(store, "free", notified::add);
        UserAccount created = svc.register("nate", "pwd12345", "内特", null);

        svc.updateLevel(created.getId(), "vip");
        // 不广播的话，同进程改完档也要等绑定缓存过期——而那层缓存本是为跨进程场景设的
        assertEquals(java.util.List.of(created.getId()), notified, "改档必须广播给配额侧失效缓存");
    }
}
