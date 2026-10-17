package com.rabbitc.ipage.sql;

import com.rabbitc.ipage.enums.SortType;
import com.rabbitc.ipage.enums.SqlFunction;


/**
 * 查询期字段登记：记录「选择列表里的别名」到「实体字段路径」的对应关系。
 * <p>
 * 结果集是按别名回填到实体的，这个对象就是别名与字段之间的桥。
 * 旧实现靠拆分下划线猜层级，遇到带下划线的表名就会错位；
 * 现在别名在生成 SQL 时就登记好了，回填不再做字符串推断。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class SelectedField {

    /**
     * 字段归属。
     */
    public enum Kind {
        /**
         * 主表上的列。
         */
        MAIN,
        /**
         * 关联表上的列，回填到实体的嵌套对象里。
         */
        ASSOCIATION,
        /**
         * 聚合值，直接回填到主表实体字段。
         */
        AGGREGATE
    }

    private final String alias;
    private final String columnExpression;
    private final String entityPath;
    private final Kind kind;
    private final Class<?> relationClass;
    private final SqlFunction function;
    private final SortType defaultSort;

    private SelectedField(String alias, String columnExpression, String entityPath, Kind kind,
                          Class<?> relationClass, SqlFunction function, SortType defaultSort) {
        this.alias = alias;
        this.columnExpression = columnExpression;
        this.entityPath = entityPath;
        this.kind = kind;
        this.relationClass = relationClass;
        this.function = function;
        this.defaultSort = defaultSort;
    }

    /**
     * 主表列。
     *
     * @param alias            选择别名
     * @param columnExpression 列表达式
     * @param entityPath       实体字段路径
     * @param defaultSort      是否默认可排序
     * @return 登记项
     */
    public static SelectedField main(String alias, String columnExpression, String entityPath, boolean defaultSort) {
        return new SelectedField(alias, columnExpression, entityPath, Kind.MAIN, null, null,
                defaultSort ? SortType.ASC : null);
    }

    /**
     * 关联表列。
     *
     * @param alias            选择别名
     * @param columnExpression 列表达式
     * @param entityPath       实体字段路径（形如 {@code sclass.sclassId}）
     * @param relationClass    关联模型类
     * @return 登记项
     */
    public static SelectedField association(String alias, String columnExpression, String entityPath,
                                            Class<?> relationClass) {
        return new SelectedField(alias, columnExpression, entityPath, Kind.ASSOCIATION, relationClass, null, null);
    }

    /**
     * 聚合值。
     *
     * @param alias            选择别名
     * @param columnExpression 聚合表达式
     * @param entityPath       实体字段名
     * @param function         聚合函数
     * @return 登记项
     */
    public static SelectedField aggregate(String alias, String columnExpression, String entityPath,
                                          SqlFunction function) {
        return new SelectedField(alias, columnExpression, entityPath, Kind.AGGREGATE, null, function, null);
    }

    /**
     * 选择别名，同时也是结果集中的列标签。
     *
     * @return 别名
     */
    public String alias() {
        return alias;
    }

    /**
     * 列表达式。
     *
     * @return 表达式
     */
    public String columnExpression() {
        return columnExpression;
    }

    /**
     * 实体字段路径。
     *
     * @return 路径
     */
    public String entityPath() {
        return entityPath;
    }

    /**
     * 归属类型。
     *
     * @return 类型
     */
    public Kind kind() {
        return kind;
    }

    /**
     * 关联模型类。
     *
     * @return 关联类，非关联字段为 {@code null}
     */
    public Class<?> relationClass() {
        return relationClass;
    }

    /**
     * 聚合函数。
     *
     * @return 聚合函数，非聚合字段为 {@code null}
     */
    public SqlFunction function() {
        return function;
    }

    /**
     * 默认排序方向。
     *
     * @return 默认可排序时返回 {@link SortType#ASC}，否则 {@code null}
     */
    public SortType defaultSort() {
        return defaultSort;
    }

    /**
     * 选择列表渲染为「expr AS alias」。
     * <p>
     * 别名若被方言加了引号，只会出现在 SQL 里；结果集的列标签是不带引号的原始名字，
     * 所以 {@link #alias()} 始终返回未加引号的形式供回填使用。
     *
     * @return SQL 片段
     */
    public String toSelectItem() {
        return columnExpression + " AS " + alias;
    }

    /**
     * 用另一个（可能带引号的）别名写法生成选择项。
     *
     * @param sqlAlias 出现在 SQL 里的别名
     * @return SQL 片段
     */
    public String toSelectItem(String sqlAlias) {
        return columnExpression + " AS " + sqlAlias;
    }

    @Override
    public String toString() {
        return alias + " <- " + columnExpression + " (" + kind + ")";
    }
}
