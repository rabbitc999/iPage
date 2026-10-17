package com.rabbitc.ipage.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字符串长度校验。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ValidatorLength {

    /**
     * 最小长度。
     *
     * @return 最小长度
     */
    int min() default 0;

    /**
     * 最大长度。
     *
     * @return 最大长度
     */
    int max() default Integer.MAX_VALUE;

    /**
     * 自定义提示信息，为空时由框架生成。
     *
     * @return 提示信息
     */
    String message() default "";
}
