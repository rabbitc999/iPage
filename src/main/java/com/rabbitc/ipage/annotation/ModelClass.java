package com.rabbitc.ipage.annotation;

import com.rabbitc.ipage.meta.ModelPermission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 模型类注解。
 * <p>
 * 标注后该类即成为一个可以被框架识别、查询、生成页面的模型。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModelClass {

    /**
     * 模型的中文名称，用于菜单与页面标题。
     *
     * @return 模型名称
     */
    String name();

    /**
     * 对应的数据库表名，为空时退化使用类名。
     *
     * @return 表名
     */
    String table() default "";

    /**
     * 查看权限。
     *
     * @return 查看所需的最低权限
     */
    ModelPermission look() default ModelPermission.LEAST;

    /**
     * 编辑权限。
     *
     * @return 编辑所需的最低权限
     */
    ModelPermission edit() default ModelPermission.LEAST;

    /**
     * 删除权限。
     *
     * @return 删除所需的最低权限
     */
    ModelPermission delete() default ModelPermission.LEAST;

    /**
     * 菜单图标（Font Awesome 类名，不含 {@code fa-} 前缀以外的东西）。
     *
     * @return 图标类名
     */
    String icon() default "fa-user-friends";

    /**
     * 模型实例的显示模板，支持 <code>${字段}</code> 与 <code>${字段.子字段}</code>。
     * <p>
     * 置空时使用实例的 {@code toString()}；模板存在时优先使用模板，
     * 这让「高一(1)班」这类复合显示写法不必再靠重写 {@code toString()} 完成。
     *
     * @return 显示模板
     */
    String text() default "";
}
