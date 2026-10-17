package com.rabbitc.ipage.annotation;

import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.enums.SqlFunction;
import com.rabbitc.ipage.meta.ModelPermission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 模型字段注解。
 * <p>
 * 标注在实体类的字段上，声明该字段在数据库中的列名、在页面上的显示方式与权限。
 * 框架在首次解析模型类时一次性读取本注解并固化成 {@link com.rabbitc.ipage.meta.FieldMeta}，
 * 之后不再做反射扫描。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ModelField {

    /**
     * 字段在数据库中的列名，必填。
     * <p>
     * 框架不做「驼峰转下划线」的推断，SQL 中的列名一律取自本属性。
     * 对于 {@link FieldType#AGGREGATION} 字段，本属性表示聚合目标表中的列名。
     *
     * @return 数据库列名
     */
    String column();

    /**
     * 列在页面上的显示名称，为空时退化使用字段名。
     *
     * @return 显示名称
     */
    String text() default "";

    /**
     * 字段语义类型，决定 SQL 生成方式与 HTML 控件类型。
     *
     * @return 字段类型
     */
    FieldType type() default FieldType.STRING;

    /**
     * 显示与排序序号，升序排列。
     *
     * @return 排序序号
     */
    int order() default 0;

    /**
     * 枚举字段所基于的枚举类。
     *
     * @return 枚举类，未指定时为 {@link Enum} 本身（表示「本字段不是枚举」）
     */
    Class<?> byEnum() default Enum.class;

    /**
     * 关联字段或聚合字段所基于的模型类。
     *
     * @return 模型类，未指定时为 {@link Object} 本身
     */
    Class<?> byModel() default Object.class;

    /**
     * 聚合时是否借子表指向本表的外键进行关联。
     * <p>
     * 例如「学生」上的「课程数量」统计的是选课表中 student_id 指向本人的记录，
     * 此时置为 {@code true}；否则按 {@link #column()} 直接聚合。
     *
     * @return 是否使用关联外键
     */
    boolean useRelatedForeignKey() default false;

    /**
     * 是否禁止新增与修改（主键、账号等不希望被表单改写的字段）。
     *
     * @return 是否固定
     */
    boolean fixed() default false;

    /**
     * 是否在表格、详情与表单中可见。为 {@code false} 时该列不渲染，
     * 但仍参与查询与数据绑定（密码一类字段用这个）。
     *
     * @return 是否可见
     */
    boolean look() default true;

    /**
     * 聚合函数，默认为 COUNT。
     *
     * @return 聚合函数
     */
    SqlFunction function() default SqlFunction.COUNT;

    /**
     * 本字段的查看权限，默认取所属模型类上的查看权限。
     *
     * @return 查看权限
     * @see ModelClass#look()
     */
    ModelPermission lookPermission() default ModelPermission.INHERIT;

    /**
     * 本字段的编辑权限，默认取所属模型类上的编辑权限。
     *
     * @return 编辑权限
     * @see ModelClass#edit()
     */
    ModelPermission editPermission() default ModelPermission.INHERIT;
}
