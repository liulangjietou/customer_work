package com.richard.fyoung.customeradmin.common.mp;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import lombok.Data;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link MyMetaObjectHandler} 单测：核心场景是"非 Web 上下文调用不应抛异常"——
 * 复现 {@code ModelConfigService#testConnectivity} 在独立线程池回调里落库触发的问题：
 * {@link cn.dev33.satoken.stp.StpUtil#isLogin()} 脱离 Servlet 线程时不是返回 {@code false}，
 * 而是抛 {@code NotWebContextException}（有 Sa-Token 上下文但当前线程没绑 request）或
 * {@code SaTokenContextException}（完全没有可用的上下文处理器，纯单测环境即如此）。
 * 纯 JUnit 单测天然不在 Web 上下文里，正好复现后一种、更彻底的失败场景。
 *
 * <p>不断言 createTime/updateTime 是否被填充——那是 MyBatis-Plus 自身
 * {@code strictInsertFill}/{@code strictUpdateFill} 对 {@link MetaObject} 的字段写入机制，
 * 依赖比这里手搭的 {@link SystemMetaObject#forObject} 更完整的 {@code TableInfo} 元数据，
 * 与本类要验证的"currentUserId() 不应让整个 fill 崩掉"这个点无关。</p>
 * @author owlzhangfq@gmail.com
 */
class MyMetaObjectHandlerTest {

    @Data
    @TableName("dummy")
    static class Dummy {
        private Long id;
        @TableField(fill = FieldFill.INSERT)
        private Long createBy;
        @TableField(fill = FieldFill.INSERT)
        private LocalDateTime createTime;
        @TableField(fill = FieldFill.INSERT_UPDATE)
        private Long updateBy;
        @TableField(fill = FieldFill.INSERT_UPDATE)
        private LocalDateTime updateTime;
    }

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Dummy.class);
    }

    private final MyMetaObjectHandler handler = new MyMetaObjectHandler();

    @Test
    void insertFill_shouldNotThrow_whenNotInWebContext() {
        MetaObject metaObject = SystemMetaObject.forObject(new Dummy());

        assertDoesNotThrow(() -> handler.insertFill(metaObject));
    }

    @Test
    void updateFill_shouldNotThrow_whenNotInWebContext() {
        MetaObject metaObject = SystemMetaObject.forObject(new Dummy());

        assertDoesNotThrow(() -> handler.updateFill(metaObject));
    }
}
