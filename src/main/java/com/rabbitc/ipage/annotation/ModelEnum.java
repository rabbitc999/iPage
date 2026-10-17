package com.rabbitc.ipage.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 枚举常量注解。
 * <p>
 * 挂在枚举常量上，声明该常量在页面上的显示文本。未标注的常量显示其 {@code name()}。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ModelEnum {

    /**
     * 显示文本。
     *
     * @return 显示文本
     */
    String text();

    /**
     * 是否在下拉选项中隐藏该常量。
     *
     * @return 是否隐藏
     */
    boolean hide() default false;
}
