package com.rabbitc.ipage.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 正则校验，仅作用于字符串字段。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ValidatorPattern {

    /**
     * 正则表达式。
     *
     * @return 正则
     */
    String regexp();

    /**
     * 自定义提示信息，为空时由框架生成。
     *
     * @return 提示信息
     */
    String message() default "";
}
