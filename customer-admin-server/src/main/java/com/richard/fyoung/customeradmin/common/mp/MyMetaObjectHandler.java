package com.richard.fyoung.customeradmin.common.mp;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 审计字段自动填充：create_by/create_time/update_by/update_time。
 *
 * <p>{@code currentUserId()} 必须能在非 Servlet 线程上安全调用——例如
 * {@code ModelConfigService#testConnectivity} 的连通性测试结果落库发生在独立线程池
 * （{@code model-test-worker}）的 {@code CompletableFuture} 回调里，而不是发起请求的 Tomcat 线程；
 * {@link StpUtil#isLogin()} 底层要读 {@code HttpServletRequest}，脱离 Web 上下文时不是返回
 * {@code false}，而是抛 {@link cn.dev33.satoken.exception.NotWebContextException}（有 Sa-Token
 * 上下文、但当前线程没绑 request）或更底层的
 * {@link cn.dev33.satoken.exception.SaTokenContextException}（完全没有可用的上下文处理器，纯单测
 * 环境即如此）——两者都继承 {@link SaTokenException}，故按 fast-fail 原则在这一处（审计填充的唯一
 * 入口）统一兜底捕获该基类，不需要每个后台线程调用点都记得处理。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        Long userId = currentUserId();
        if (userId != null) {
            this.strictInsertFill(metaObject, "createBy", Long.class, userId);
            this.strictInsertFill(metaObject, "updateBy", Long.class, userId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        Long userId = currentUserId();
        if (userId != null) {
            this.strictUpdateFill(metaObject, "updateBy", Long.class, userId);
        }
    }

    /** 当前登录用户 ID；未登录或不在 Web 上下文中（如后台线程池的异步回调）时返回 null，不强行填充。 */
    private Long currentUserId() {
        try {
            if (!StpUtil.isLogin()) {
                return null;
            }
            return StpUtil.getLoginIdAsLong();
        } catch (SaTokenException e) {
            return null;
        }
    }
}
