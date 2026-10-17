package com.rabbitc.ipage.enums;

/**
 * 查询条件操作符。
 * <p>
 * 数值只允许通过 {@link #getSymbol()} 进入 SQL，操作符本身来自这个封闭枚举，
 * 不接受任何来自请求参数的原始字符串。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public enum OperatorType {
    /**
     * 等于。
     */
    EQ("=", "等于"),
    /**
     * 不等于。
     */
    NE("<>", "不等于"),
    /**
     * 大于。
     */
    GT(">", "大于"),
    /**
     * 小于。
     */
    LT("<", "小于"),
    /**
     * 大于等于。
     */
    GTE(">=", "不小于"),
    /**
     * 小于等于。
     */
    LTE("<=", "不大于"),
    /**
     * 模糊匹配，值两侧自动补 {@code %}。
     */
    LIKE("LIKE", "包含"),
    /**
     * 以某串开头。
     */
    STARTS_WITH("LIKE", "开头是"),
    /**
     * 以某串结尾。
     */
    ENDS_WITH("LIKE", "结尾是"),
    /**
     * 为空。
     */
    IS_NULL("IS NULL", "为空"),
    /**
     * 不为空。
     */
    IS_NOT_NULL("IS NOT NULL", "不为空"),
    /**
     * 属于给定集合。
     */
    IN("IN", "属于"),
    /**
     * 不属于给定集合。
     */
    NOT_IN("NOT IN", "不属于");

    private final String symbol;
    private final String label;

    OperatorType(String symbol, String label) {
        this.symbol = symbol;
        this.label = label;
    }

    /**
     * 中文标签，供搜索栏的操作符下拉框直接显示。
     *
     * @return 标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * SQL 中对应的符号。
     *
     * @return 符号
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * 该操作符是否需要取值。
     *
     * @return 需要取值返回 {@code true}
     */
    public boolean needsValue() {
        return this != IS_NULL && this != IS_NOT_NULL;
    }

    /**
     * 该操作符的值是否是一组值（用于 {@code IN} / {@code NOT IN}）。
     *
     * @return 需要集合返回 {@code true}
     */
    public boolean needsCollection() {
        return this == IN || this == NOT_IN;
    }

    /**
     * 按枚举名宽松解析，无法识别时退回 {@link #EQ}。
     *
     * @param name 枚举名，忽略大小写
     * @return 操作符
     */
    public static OperatorType parse(String name) {
        if (name == null || name.isBlank()) {
            return EQ;
        }
        String normalized = name.trim().toUpperCase();
        for (OperatorType type : values()) {
            if (type.name().equals(normalized) || type.symbol.equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return EQ;
    }
}
