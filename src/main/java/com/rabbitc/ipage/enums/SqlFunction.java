package com.rabbitc.ipage.enums;

/**
 * 聚合函数。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public enum SqlFunction {
    /**
     * 计数。聚合时自动加 {@code DISTINCT}，避免 JOIN 造成的重复行把数量放大。
     */
    COUNT("COUNT"),
    /**
     * 求和。
     */
    SUM("SUM"),
    /**
     * 平均值。
     */
    AVG("AVG"),
    /**
     * 最大值。
     */
    MAX("MAX"),
    /**
     * 最小值。
     */
    MIN("MIN");

    private final String sqlName;

    SqlFunction(String sqlName) {
        this.sqlName = sqlName;
    }

    /**
     * SQL 中的函数名。
     *
     * @return 函数名
     */
    public String getSqlName() {
        return sqlName;
    }
}
