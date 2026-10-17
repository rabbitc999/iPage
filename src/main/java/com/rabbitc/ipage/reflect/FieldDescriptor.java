package com.rabbitc.ipage.reflect;

import com.rabbitc.ipage.annotation.ModelField;
import com.rabbitc.ipage.exception.ModelDefinitionException;
import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.enums.SqlFunction;
import com.rabbitc.ipage.meta.ModelPermission;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 字段描述符：把一个 {@link Field} 及其上的注解固化成不可变对象。
 * <p>
 * 框架内所有需要「字段名 / 列名 / 类型 / 显示名」的地方都读这里，
 * 不再各自调用 {@code getAnnotation} 或做字符串推断。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class FieldDescriptor {

    private final Field field;
    private final String name;
    private final String column;
    private final String text;
    private final FieldType type;
    private final int order;
    private final boolean fixed;
    private final boolean visible;
    private final boolean useRelatedForeignKey;
    private final SqlFunction function;
    private final Class<?> ownersClass;
    private final Class<?> relatedClass;
    private final Class<?> enumClass;
    private final ModelPermission lookPermission;
    private final ModelPermission editPermission;
    private final List<Annotation> validators;
    private final Class<?> javaType;

    FieldDescriptor(Field field, ModelField annotation, Class<?> ownersClass,
                    ModelPermission ownerLook, ModelPermission ownerEdit, List<Annotation> validators) {
        this.field = field;
        this.field.setAccessible(true);
        this.name = field.getName();
        this.javaType = field.getType();
        this.column = annotation.column() == null ? "" : annotation.column().trim();
        this.text = annotation.text() == null || annotation.text().isBlank() ? name : annotation.text();
        this.type = annotation.type();
        this.order = annotation.order();
        this.fixed = annotation.fixed();
        this.visible = annotation.look();
        this.useRelatedForeignKey = annotation.useRelatedForeignKey();
        this.function = annotation.function();
        this.ownersClass = ownersClass;
        this.relatedClass = annotation.byModel() == Object.class ? null : annotation.byModel();
        this.enumClass = resolveEnumClass(annotation.byEnum());
        this.lookPermission = annotation.lookPermission() == ModelPermission.INHERIT
                ? ownerLook : annotation.lookPermission();
        this.editPermission = annotation.editPermission() == ModelPermission.INHERIT
                ? ownerEdit : annotation.editPermission();
        this.validators = List.copyOf(validators);
    }

    /**
     * Java 字段名。
     *
     * @return 字段名
     */
    public String name() {
        return name;
    }

    /**
     * 数据库列名。
     *
     * @return 列名
     */
    public String column() {
        return column;
    }

    /**
     * 页面显示名。
     *
     * @return 显示名
     */
    public String text() {
        return text;
    }

    /**
     * 字段语义类型。
     *
     * @return 类型
     */
    public FieldType type() {
        return type;
    }

    /**
     * 排序序号。
     *
     * @return 序号
     */
    public int order() {
        return order;
    }

    /**
     * 是否禁止编辑。
     *
     * @return 禁止编辑返回 {@code true}
     */
    public boolean fixed() {
        return fixed;
    }

    /**
     * 是否可见。
     *
     * @return 可见返回 {@code true}
     */
    public boolean visible() {
        return visible;
    }

    /**
     * 聚合时是否借子表外键关联。
     *
     * @return 使用外键关联返回 {@code true}
     */
    public boolean useRelatedForeignKey() {
        return useRelatedForeignKey;
    }

    /**
     * 聚合函数。
     *
     * @return 聚合函数
     */
    public SqlFunction function() {
        return function;
    }

    /**
     * 声明本字段的模型类。
     *
     * @return 模型类
     */
    public Class<?> ownersClass() {
        return ownersClass;
    }

    /**
     * 关联的模型类，非关联字段为 {@code null}。
     *
     * @return 关联模型类
     */
    public Class<?> relatedClass() {
        return relatedClass;
    }

    /**
     * 关联的枚举类，非枚举字段为 {@code null}。
     *
     * @return 枚举类
     */
    public Class<?> enumClass() {
        return enumClass;
    }

    /**
     * 查看权限。
     *
     * @return 查看权限
     */
    public ModelPermission lookPermission() {
        return lookPermission;
    }

    /**
     * 编辑权限。
     *
     * @return 编辑权限
     */
    public ModelPermission editPermission() {
        return editPermission;
    }

    /**
     * 字段上的校验注解，按声明顺序。
     *
     * @return 校验注解列表
     */
    public List<Annotation> validators() {
        return validators;
    }

    /**
     * Java 字段类型。
     *
     * @return 类型
     */
    public Class<?> javaType() {
        return javaType;
    }

    /**
     * 是否对应表中的真实列。
     *
     * @return 真实列返回 {@code true}
     */
    public boolean isColumn() {
        return type.isColumn();
    }

    /**
     * 读取字段值。
     *
     * @param target 目标对象
     * @return 字段值
     */
    public Object read(Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "无法读取字段 " + ownersClass.getSimpleName() + "." + name, e);
        }
    }

    /**
     * 写入字段值，用于查询结果映射与请求绑定。
     *
     * @param target 目标对象
     * @param value  字段值
     */
    public void write(Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "无法写入字段 " + ownersClass.getSimpleName() + "." + name
                    + "（目标类型 " + javaType.getSimpleName() + "）", e);
        }
    }

    @Override
    public String toString() {
        return ownersClass.getSimpleName() + "." + name + "(" + column + ", " + type + ")";
    }

    private static Class<?> resolveEnumClass(Class<?> declared) {
        if (declared == null || declared == Enum.class) {
            return null;
        }
        if (!declared.isEnum()) {
            throw new ModelDefinitionException("@ModelField.byEnum() 必须指向一个枚举类，实际为 "
                                               + declared.getName());
        }
        return declared;
    }
}
