package com.rabbitc.ipage.sql;

import com.rabbitc.ipage.enums.OperatorType;

import java.util.Objects;

/**
 * 单个查询条件。
 * <p>
 * 列名由框架从元数据解析得到，操作符来自封闭枚举，值一律走 JDBC 占位符；
 * 三者都不接受来自请求的原始 SQL 片段。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class SqlCondition {

    private final String columnExpression;
    private final OperatorType operator;
    private final Object value;

    /**
     * 构造函数。
     *
     * @param columnExpression 已经带别名限定的列表达式，例如 {@code s.student_name}
     * @param operator         操作符
     * @param value            值，{@link OperatorType#IS_NULL} 系列可为 {@code null}
     */
    public SqlCondition(String columnExpression, OperatorType operator, Object value) {
        this.columnExpression = columnExpression;
        this.operator = operator == null ? OperatorType.EQ : operator;
        this.value = value;
    }

    /**
     * 列表达式。
     *
     * @return 列表达式
     */
    public String columnExpression() {
        return columnExpression;
    }

    /**
     * 操作符。
     *
     * @return 操作符
     */
    public OperatorType operator() {
        return operator;
    }

    /**
     * 参数值。
     *
     * @return 值
     */
    public Object value() {
        return value;
    }

    /**
     * 是否为空值判断类操作符。
     *
     * @return 不需要参数返回 {@code true}
     */
    public boolean isNullCheck() {
        return !operator.needsValue();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SqlCondition other
               && columnExpression.equals(other.columnExpression)
               && operator == other.operator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnExpression, operator);
    }

    @Override
    public String toString() {
        return isNullCheck()
                ? columnExpression + " " + operator.getSymbol()
                : columnExpression + " " + operator.getSymbol() + " " + value;
    }
}
