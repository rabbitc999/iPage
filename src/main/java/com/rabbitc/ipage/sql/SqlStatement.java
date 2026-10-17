package com.rabbitc.ipage.sql;

import com.rabbitc.ipage.dialect.Dialect;
import com.rabbitc.ipage.reflect.ModelMeta;
import com.rabbitc.ipage.reflect.FieldDescriptor;
import com.rabbitc.ipage.reflect.ValueConverter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性拼好的 SQL 与参数。
 * <p>
 * 宿主应用执行 SQL 只需要两样东西：{@link #sql()} 与 {@link #parameters()}，
 * 参数按占位符出现顺序排列，直接 {@code PreparedStatement.setObject(i, ...)} 即可。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class SqlStatement {

    private final String sql;
    private final List<Object> parameters;

    /**
     * 构造函数。
     *
     * @param sql        SQL 文本
     * @param parameters 与占位符一一对应的参数
     */
    public SqlStatement(String sql, List<Object> parameters) {
        this.sql = sql;
        // 参数里合法地存在 null（可空列），因此不能用 List.copyOf
        this.parameters = java.util.Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    /**
     * SQL 文本，使用 {@code ?} 占位符。
     *
     * @return SQL
     */
    public String sql() {
        return sql;
    }

    /**
     * 参数列表。
     *
     * @return 参数
     */
    public List<Object> parameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return sql;
    }

    // ------------------------------------------------------------------ 构建器

    /**
     * SQL 构建器，负责片段拼接与参数收集。
     */
    public static final class Builder {
        private final StringBuilder sql = new StringBuilder(512);
        private final List<Object> parameters = new ArrayList<>(16);
        private final Map<String, Object> named = new LinkedHashMap<>();

        /**
         * 追加 SQL 片段。
         *
         * @param fragment 片段
         * @return 当前构建器
         */
        public Builder append(String fragment) {
            sql.append(fragment);
            return this;
        }

        /**
         * 追加单个字符，便于拼装分隔符而不用构造字符串。
         *
         * @param fragment 字符
         * @return 当前构建器
         */
        public Builder append(char fragment) {
            sql.append(fragment);
            return this;
        }

        /**
         * 追加一个占位符及其参数。
         *
         * @param value 参数
         * @return 当前构建器
         */
        public Builder bind(Object value) {
            sql.append('?');
            parameters.add(ValueConverter.toDatabase(value));
            return this;
        }

        /**
         * 追加 {@code name = ?} 形式的赋值。
         *
         * @param column 列名
         * @param value  值
         * @return 当前构建器
         */
        public Builder assign(String column, Object value) {
            sql.append(column).append(" = ");
            return bind(value);
        }

        /**
         * 追加一个条件。
         *
         * @param condition 条件
         * @return 当前构建器
         */
        public Builder condition(SqlCondition condition) {
            sql.append(condition.columnExpression()).append(' ').append(condition.operator().getSymbol());
            if (condition.isNullCheck()) {
                return this;
            }
            if (condition.operator().needsCollection()) {
                java.util.List<?> items = condition.value() instanceof java.util.Collection<?> collection
                        ? new java.util.ArrayList<>(collection)
                        : java.util.List.of(condition.value());
                if (items.isEmpty()) {
                    // 空集合没有匹配语义：拼一个恒假条件，避免生成 `IN ()` 这种非法 SQL
                    sql.append(" (NULL)");
                    return this;
                }
                sql.append(" (");
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    bind(items.get(i));
                }
                sql.append(')');
                return this;
            }
            String raw = String.valueOf(condition.value());
            switch (condition.operator()) {
                case LIKE -> {
                    sql.append(" CONCAT('%', ?, '%')");
                    parameters.add(ValueConverter.toDatabase(raw));
                }
                case STARTS_WITH -> {
                    sql.append(" CONCAT(?, '%')");
                    parameters.add(ValueConverter.toDatabase(raw));
                }
                case ENDS_WITH -> {
                    sql.append(" CONCAT('%', ?)");
                    parameters.add(ValueConverter.toDatabase(raw));
                }
                default -> {
                    sql.append(' ');
                    bind(condition.value());
                }
            }
            return this;
        }

        /**
         * 追加逗号分隔的列表。
         *
         * @param items 元素
         * @return 当前构建器
         */
        public Builder joinComma(List<String> items) {
            sql.append(String.join(", ", items));
            return this;
        }

        /**
         * 记录名称到值的映射，便于调试与日志。
         *
         * @param name  名称
         * @param value 值
         * @return 当前构建器
         */
        public Builder describe(String name, Object value) {
            named.put(name, value);
            return this;
        }

        /**
         * 生成不可变语句。
         *
         * @return 语句
         */
        public SqlStatement build() {
            return new SqlStatement(sql.toString(), parameters);
        }
    }

    // ------------------------------------------------------------------ 语句工厂

    /**
     * 按查询对象生成 SELECT。
     *
     * @param query     查询
     * @param withWhere 是否带 WHERE
     * @return 语句
     */
    public static SqlStatement select(Query query, boolean withWhere) {
        return select(query, withWhere, Dialect.DEFAULT);
    }

    /**
     * 按查询对象生成 SELECT，分页语法交给方言。
     *
     * @param query     查询
     * @param withWhere 是否带 WHERE
     * @param dialect   方言
     * @return 语句
     */
    public static SqlStatement select(Query query, boolean withWhere, Dialect dialect) {
        ModelMeta meta = query.meta();
        List<String> items = new ArrayList<>();
        for (SelectedField field : query.selectedFields()) {
            items.add(field.toSelectItem(dialect.quote(field.alias())));
        }
        Builder builder = new Builder()
                .append("SELECT ")
                .joinComma(items)
                .append(" FROM ")
                .append(tableRef(meta.table(), dialect))
                .append(" ")
                .append(query.baseAlias());
        appendJoins(builder, query, dialect);
        if (withWhere) {
            appendWhere(builder, query);
        }
        appendGroupAndHaving(builder, query);
        if (withWhere) {
            appendOrderAndPage(builder, query, dialect);
        }
        return builder.build();
    }

    /**
     * 按查询对象生成 COUNT 语句，用于分页总数。
     *
     * @param query 查询
     * @return 语句
     */
    public static SqlStatement count(Query query) {
        return count(query, Dialect.DEFAULT);
    }

    /**
     * 按查询对象生成 COUNT 语句，标识符与分页语法交给方言。
     * <p>
     * 带聚合列的查询要数「分组后的组数」，所以套一层子查询；子查询的别名固定为
     * {@code ipage_count}，它在所有方言里都是合法标识符。
     *
     * @param query   查询
     * @param dialect 方言
     * @return 语句
     */
    public static SqlStatement count(Query query, Dialect dialect) {
        ModelMeta meta = query.meta();
        Builder builder = new Builder();
        if (query.hasAggregates()) {
            builder.append("SELECT COUNT(*) FROM (SELECT ")
                   .append(qualified(query.baseAlias(), meta.idColumn(), dialect))
                   .append(" FROM ")
                   .append(tableRef(meta.table(), dialect))
                   .append(" ")
                   .append(query.baseAlias());
            appendJoins(builder, query, dialect);
            appendWhere(builder, query);
            appendGroupAndHaving(builder, query);
            builder.append(") ipage_count");
        } else {
            builder.append("SELECT COUNT(*) FROM ")
                   .append(tableRef(meta.table(), dialect))
                   .append(" ")
                   .append(query.baseAlias());
            appendJoins(builder, query, dialect);
            appendWhere(builder, query);
        }
        return builder.build();
    }

    /**
     * 按主键查询单条。
     *
     * @param query 查询
     * @param id    主键值
     * @return 语句
     */
    public static SqlStatement selectById(Query query, Object id) {
        return selectById(query, id, Dialect.DEFAULT);
    }

    /**
     * 按主键查询单条，表名与列名按方言加引号。
     *
     * @param query   查询
     * @param id      主键值
     * @param dialect 方言
     * @return 语句
     */
    public static SqlStatement selectById(Query query, Object id, Dialect dialect) {
        Builder builder = new Builder()
                .append(selectColumns(query, dialect))
                .append(" FROM ")
                .append(tableRef(query.meta().table(), dialect))
                .append(" ")
                .append(query.baseAlias());
        appendJoins(builder, query, dialect);
        builder.append(" WHERE ")
               .assign(qualified(query.baseAlias(), query.meta().idColumn(), dialect), id);
        appendGroupAndHaving(builder, query);
        return builder.build();
    }

    private static String selectColumns(Query query, Dialect dialect) {
        List<String> items = new ArrayList<>();
        for (SelectedField field : query.selectedFields()) {
            items.add(field.toSelectItem(dialect.quote(field.alias())));
        }
        return "SELECT " + String.join(", ", items);
    }

    /**
     * 生成 INSERT。
     *
     * @param meta   元数据
     * @param values 列名到值的映射，保持插入顺序
     * @return 语句
     */
    public static SqlStatement insert(ModelMeta meta, Map<String, Object> values) {
        return insert(meta, values, Dialect.DEFAULT);
    }

    /**
     * 生成 INSERT，标识符按方言加引号。
     *
     * @param meta    元数据
     * @param values  列名到值的映射，保持插入顺序
     * @param dialect 方言
     * @return 语句
     */
    public static SqlStatement insert(ModelMeta meta, Map<String, Object> values, Dialect dialect) {
        List<String> columns = new ArrayList<>(values.keySet());
        Builder builder = new Builder()
                .append("INSERT INTO ")
                .append(tableRef(meta.table(), dialect))
                .append(" (");
        boolean firstColumn = true;
        for (String column : columns) {
            if (!firstColumn) {
                builder.append(", ");
            }
            firstColumn = false;
            builder.append(quoteColumn(column, dialect));
        }
        builder.append(") VALUES (");
        boolean first = true;
        for (String column : columns) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.bind(values.get(column));
        }
        builder.append(")");
        return builder.build();
    }

    /**
     * 生成 UPDATE。
     *
     * @param meta      元数据
     * @param values    待更新列
     * @param idColumn  主键列
     * @param id       主键值
     * @return 语句
     */
    public static SqlStatement update(ModelMeta meta, Map<String, Object> values, String idColumn, Object id) {
        return update(meta, values, idColumn, id, Dialect.DEFAULT);
    }

    /**
     * 生成 UPDATE，标识符按方言加引号。
     *
     * @param meta     元数据
     * @param values   待更新列
     * @param idColumn 主键列
     * @param id       主键值
     * @param dialect  方言
     * @return 语句
     */
    public static SqlStatement update(ModelMeta meta, Map<String, Object> values, String idColumn, Object id,
                                      Dialect dialect) {
        Builder builder = new Builder()
                .append("UPDATE ")
                .append(tableRef(meta.table(), dialect))
                .append(" SET ");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.assign(quoteColumn(entry.getKey(), dialect), entry.getValue());
        }
        builder.append(" WHERE ");
        builder.condition(new SqlCondition(quoteColumn(idColumn, dialect),
                com.rabbitc.ipage.enums.OperatorType.EQ, id));
        return builder.build();
    }

    /**
     * 生成按主键 DELETE。
     *
     * @param meta     元数据
     * @param idColumn 主键列
     * @param id       主键值
     * @return 语句
     */
    public static SqlStatement delete(ModelMeta meta, String idColumn, Object id) {
        return delete(meta, idColumn, id, Dialect.DEFAULT);
    }

    /**
     * 生成按主键 DELETE，标识符按方言加引号。
     *
     * @param meta     元数据
     * @param idColumn 主键列
     * @param id       主键值
     * @param dialect  方言
     * @return 语句
     */
    public static SqlStatement delete(ModelMeta meta, String idColumn, Object id, Dialect dialect) {
        Builder builder = new Builder()
                .append("DELETE FROM ")
                .append(tableRef(meta.table(), dialect))
                .append(" WHERE ");
        builder.condition(new SqlCondition(quoteColumn(idColumn, dialect),
                com.rabbitc.ipage.enums.OperatorType.EQ, id));
        return builder.build();
    }

    /**
     * 生成按条件 DELETE。
     *
     * @param meta       元数据
     * @param conditions 条件；为空时拒绝生成，避免整表删除
     * @return 语句
     */
    public static SqlStatement deleteWhere(ModelMeta meta, List<SqlCondition> conditions) {
        return deleteWhere(meta, conditions, Dialect.DEFAULT);
    }

    /**
     * 生成按条件 DELETE，标识符按方言加引号。
     *
     * @param meta       元数据
     * @param conditions 条件；为空时拒绝生成，避免整表删除
     * @param dialect    方言
     * @return 语句
     */
    public static SqlStatement deleteWhere(ModelMeta meta, List<SqlCondition> conditions, Dialect dialect) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("拒绝生成无条件的 DELETE 语句");
        }
        Builder builder = new Builder()
                .append("DELETE FROM ")
                .append(tableRef(meta.table(), dialect))
                .append(" WHERE ");
        appendConditions(builder, conditions);
        return builder.build();
    }

    /**
     * 生成按字段取「值-显示文本」对的语句，供下拉框使用。
     *
     * @param meta      元数据
     * @param labelExpr 显示列表达式（未配置时为 {@code null}）
     * @return 语句
     */
    public static SqlStatement options(ModelMeta meta, String labelExpr) {
        return options(meta, labelExpr, null, Dialect.DEFAULT);
    }

    /**
     * 生成按字段取「值-显示文本」对的语句。
     *
     * @param meta      元数据
     * @param labelExpr 显示列表达式（未配置时为 {@code null}，退化为按主键显示）
     * @param orderBy   排序子句，可为空串或 {@code null}
     * @param dialect   方言
     * @return 语句
     */
    public static SqlStatement options(ModelMeta meta, String labelExpr, String orderBy, Dialect dialect) {
        String target = labelExpr == null || labelExpr.isBlank() ? meta.idColumn() : labelExpr;
        return new Builder()
                .append("SELECT ")
                .append(qualified(meta.table(), meta.idColumn(), dialect))
                .append(", ")
                .append(qualified(meta.table(), target, dialect))
                .append(" FROM ")
                .append(tableRef(meta.table(), dialect))
                .append(orderBy == null || orderBy.isBlank() ? "" : " ORDER BY " + orderBy)
                .build();
    }

    /**
     * 判断列名是否安全，防止元数据被外部改写后拼出异常 SQL。
     *
     * @param identifier 标识符
     * @return 安全返回 {@code true}
     */
    public static boolean isSafeIdentifier(String identifier) {
        return identifier != null && identifier.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /**
     * 校验标识符，非法直接抛异常。
     *
     * @param identifier 标识符
     * @param what       用途说明
     * @return 原值
     */
    public static String requireIdentifier(String identifier, String what) {
        if (!isSafeIdentifier(identifier)) {
            throw new IllegalArgumentException(what + "「" + identifier + "」不是合法的 SQL 标识符");
        }
        return identifier;
    }

    /**
     * 取字段的表列名，顺带做一次安全校验。
     *
     * @param field 字段
     * @return 列名
     */
    public static String columnOf(FieldDescriptor field) {
        return requireIdentifier(field.column(), "列名");
    }

    private static String qualified(String alias, String column) {
        return qualified(alias, column, Dialect.DEFAULT);
    }

    /**
     * 拼「别名.列」，列名按方言加引号。
     *
     * @param alias   别名
     * @param column  列名
     * @param dialect 方言
     * @return 限定的列引用
     */
    private static String qualified(String alias, String column, Dialect dialect) {
        return alias + "." + quoteColumn(column, dialect);
    }

    /**
     * 表引用：先做一次安全校验，再按方言加引号。
     * <p>
     * PostgreSQL 里不加引号的表名会被折叠成小写，建表时若加了引号，
     * 查询时不加引号就查不到——两处必须用同一套规则。
     *
     * @param table   表名
     * @param dialect 方言
     * @return 表引用
     */
    private static String tableRef(String table, Dialect dialect) {
        return dialect.quote(requireIdentifier(table, "表名"));
    }

    /**
     * 列引用：先校验再按方言加引号。
     *
     * @param column  列名
     * @param dialect 方言
     * @return 列引用
     */
    private static String quoteColumn(String column, Dialect dialect) {
        return dialect.quote(requireIdentifier(column, "列名"));
    }

    private static void appendJoins(Builder builder, Query query) {
        appendJoins(builder, query, Dialect.DEFAULT);
    }

    private static void appendJoins(Builder builder, Query query, Dialect dialect) {
        for (JoinCondition join : query.joins()) {
            builder.append(" LEFT JOIN ")
                   .append(tableRef(join.getJoinTable(), dialect))
                   .append(" ")
                   .append(join.getAlias())
                   .append(" ON ")
                   .append(join.getOnCondition());
        }
    }

    private static void appendWhere(Builder builder, Query query) {
        if (query.whereConditions().isEmpty()) {
            return;
        }
        builder.append(" WHERE ");
        appendConditions(builder, query.whereConditions());
    }

    private static void appendConditions(Builder builder, List<SqlCondition> conditions) {
        boolean first = true;
        for (SqlCondition condition : conditions) {
            if (!first) {
                builder.append(" AND ");
            }
            first = false;
            builder.condition(condition);
        }
    }

    private static void appendGroupAndHaving(Builder builder, Query query) {
        if (!query.groupByColumns().isEmpty()) {
            builder.append(" GROUP BY ");
            boolean first = true;
            for (String column : query.groupByColumns()) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                builder.append(column);
            }
        }
        if (!query.havingConditions().isEmpty()) {
            builder.append(" HAVING ");
            appendConditions(builder, query.havingConditions());
        }
    }

    private static void appendOrderAndPage(Builder builder, Query query, Dialect dialect) {
        if (!query.orderByMap().isEmpty()) {
            builder.append(" ORDER BY ");
            boolean first = true;
            for (Map.Entry<String, com.rabbitc.ipage.enums.SortType> entry : query.orderByMap().entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                builder.append(entry.getKey()).append(' ').append(entry.getValue().name());
            }
        }
        if (query.limit() != null) {
            // 占位符由 bind 自己写，这里不能再预写 "?"，否则参数个数与占位符个数对不上。
            // 具体写法交给方言：PostgreSQL 9.5+ 与 MySQL 用 LIMIT/OFFSET，老版 Oracle 用 OFFSET ... FETCH。
            String clause = dialect.limitClause(query.limit(), query.offset());
            int first = clause.indexOf('?');
            int second = clause.lastIndexOf('?');
            builder.append(clause.substring(0, first));
            builder.bind(query.limit());
            builder.append(clause.substring(first + 1, second < 0 ? clause.length() : second));
            if (second >= 0) {
                builder.bind(query.offset());
            }
        }
    }
}
