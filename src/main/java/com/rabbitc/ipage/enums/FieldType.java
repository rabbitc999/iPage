package com.rabbitc.ipage.enums;

/**
 * 字段语义类型。
 * <p>
 * 决定该字段是否落库、SQL 中如何被选取、以及表单里渲染成什么控件。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public enum FieldType {
    /**
     * 主键列，落库、可排序、表单中不可编辑。
     */
    MODEL_ID,
    /**
     * 外键列，落库；表单中渲染为下拉框，选项来自 {@code byModel} 的全部记录。
     */
    ASSOCIATION_ID,
    /**
     * 关联对象，不落库；查询时通过 LEFT JOIN 填充，页面中显示为可点击的对象。
     */
    ASSOCIATION_MODEL,
    /**
     * 聚合列，不落库；查询时以 {@code function} 聚合 {@code byModel} 对应表中的 {@code column}。
     */
    AGGREGATION,
    /**
     * 字符串。
     */
    STRING,
    /**
     * 数值。
     */
    NUMBER,
    /**
     * 枚举，落库为枚举常量名。
     */
    ENUM,
    /**
     * 日期。
     */
    DATE,
    /**
     * 布尔值。
     */
    BOOLEAN;

    /**
     * 该类型是否对应表中的一个真实列。
     *
     * @return 是真实列返回 {@code true}
     */
    public boolean isColumn() {
        return this == MODEL_ID || this == ASSOCIATION_ID || this == STRING
               || this == NUMBER || this == ENUM || this == DATE || this == BOOLEAN;
    }

    /**
     * 该类型是否是写入数据库时参与 INSERT / UPDATE 的列。
     * <p>
     * 关联对象与聚合值是查询期算出来的，不属于表结构。
     *
     * @return 可写入返回 {@code true}
     */
    public boolean isWritable() {
        return isColumn();
    }
}
