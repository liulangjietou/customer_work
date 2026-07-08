package com.richard.fyoung.customeradmin.common.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 Controller 方法上，触发 {@link OperationLogAspect} 自动记录操作日志。
 * @author owlzhangfq@gmail.com
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /** 操作描述，如"新建模型配置"。 */
    String operation();

    /** 操作对象类型，如 "ai_model_config"；默认取所在类的简单名。 */
    String target() default "";
}
