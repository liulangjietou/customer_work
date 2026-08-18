package com.richard.fyoung.customerwork.data.user;

import com.richard.fyoung.customerwork.safety.subjectquota.SubjectLevelBinding;

import java.util.Optional;

/**
 * 从账户表读取用户的配额等级绑定（{@link SubjectLevelBinding} 在本项目的唯一实现）。
 *
 * <p>薄薄一层适配：配额领域只想知道"这个用户是哪一档"，把整个账户实体递过去会让它认识
 * 密码哈希、头像这些与限流无关的东西。查询频次由 {@code SubjectLevelResolver} 的本地缓存兜住，
 * 本类不再自建一层缓存——两层缓存会让"改了等级多久生效"变成没人算得清的问题。</p>
 * @author owlzhangfq@gmail.com
 */
public class UserAccountLevelBinding implements SubjectLevelBinding {

    private final UserAccountStore store;

    public UserAccountLevelBinding(UserAccountStore store) {
        this.store = store;
    }

    @Override
    public Optional<String> levelCodeOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return store.findById(userId)
            .map(UserAccount::getLevelCode)
            .filter(code -> !code.isBlank());
    }
}
