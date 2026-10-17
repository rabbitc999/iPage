package com.rabbitc.ipage.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数值范围校验，仅作用于数值字段。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ValidatorRange {

    /**
     * 最小值。
     *
     * @return 最小值
     */
    long min() default Long.MIN_VALUE;

    /**
     * 最大值。
     *
     * @return 最大值
     */
    long max() default Long.MAX_VALUE;

    /**
     * 自定义提示信息，为空时由框架生成。
     *
     * @return 提示信息
     */
    String message() default "";
}
