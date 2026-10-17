package com.rabbitc.ipage.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 非空校验。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface ValidatorNotNull {

    /**
     * 自定义提示信息，为空时由框架按字段显示名生成。
     *
     * @return 提示信息
     */
    String message() default "";
}
