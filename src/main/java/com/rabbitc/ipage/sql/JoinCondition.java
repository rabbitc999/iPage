package com.rabbitc.ipage.sql;

/**
 * LEFT JOIN 条件。
 * <p>
 * 表别名与连接条件均由框架在解析期生成，{@code onCondition} 只包含
 * 「别名.列 = 别名.列」，不含任何来自请求的输入。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class JoinCondition {

    private final String joinTable;
    private final String alias;
    private final String onCondition;

    /**
     * 构造函数。
     *
     * @param joinTable   被连接的表名
     * @param alias       该表在本次查询中的别名
     * @param onCondition 连接条件
     */
    public JoinCondition(String joinTable, String alias, String onCondition) {
        this.joinTable = joinTable;
        this.alias = alias;
        this.onCondition = onCondition;
    }

    /**
     * 被连接的表名。
     *
     * @return 表名
     */
    public String getJoinTable() {
        return joinTable;
    }

    /**
     * 表别名。
     *
     * @return 别名
     */
    public String getAlias() {
        return alias;
    }

    /**
     * 连接条件。
     *
     * @return 条件
     */
    public String getOnCondition() {
        return onCondition;
    }

    @Override
    public String toString() {
        return "LEFT JOIN " + joinTable + " " + alias + " ON " + onCondition;
    }
}
