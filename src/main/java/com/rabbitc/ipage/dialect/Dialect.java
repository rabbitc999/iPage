package com.rabbitc.ipage.dialect;

/**
 * 数据库方言。
 * <p>
 * 框架生成的 SQL 只在少数几个点上与具体数据库有关，方言把这几个点收敛到一个接口里：
 * <ul>
 *     <li>标识符是否需要引号（PostgreSQL 会把未加引号的名字折叠成小写）；</li>
 *     <li>分页语法（{@code LIMIT ? OFFSET ?} 与 {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}）；</li>
 *     <li>主键自增列的声明方式（{@code SERIAL} / {@code AUTO_INCREMENT} / {@code AUTOINCREMENT}）；</li>
 *     <li>Java 类型到列类型的映射，供动态建表使用。</li>
 * </ul>
 * 实现必须无状态且线程安全。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public interface Dialect {

    /**
     * 默认方言：不加引号、{@code LIMIT ? OFFSET ?}，适用于 MySQL / H2 / SQLite。
     */
    Dialect DEFAULT = new Dialect() {
        @Override
        public String name() {
            return "default";
        }

        @Override
        public String idColumnType(Class<?> javaType, boolean autoIncrement) {
            return (javaType == Long.class || javaType == long.class ? "BIGINT" : "INT")
                   + (autoIncrement ? " AUTO_INCREMENT PRIMARY KEY" : " PRIMARY KEY");
        }

        @Override
        public String columnType(Class<?> javaType) {
            return javaType == null ? "VARCHAR(255)" : "VARCHAR(255)";
        }

        @Override
        public String tableExistsQuery(String table) {
            return "SELECT 1 FROM information_schema.tables WHERE table_name = '" + table + "'";
        }

        @Override
        public String columnsQuery(String table) {
            return "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'";
        }
    };

    /**
     * 方言名称，用于日志与配置回显。
     *
     * @return 名称
     */
    String name();

    /**
     * 是否给标识符加引号。
     * <p>
     * PostgreSQL 中未加引号的标识符会被折叠为小写，模型中若写了大写列名就会找不到列；
     * 这里默认 {@code false}，由 PostgreSQL 方言覆写为 {@code true}。
     *
     * @return 需要引号返回 {@code true}
     */
    default boolean quotesIdentifiers() {
        return false;
    }

    /**
     * 引用一个标识符（表名、列名、别名）。
     *
     * @param identifier 标识符
     * @return 可直接放进 SQL 的标识符
     */
    default String quote(String identifier) {
        if (!quotesIdentifiers() || identifier == null) {
            return identifier;
        }
        if (identifier.startsWith("\"") && identifier.endsWith("\"")) {
            return identifier;
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * 渲染分页子句。
     * <p>
     * 返回的文本用 {@code ?} 占位，参数顺序固定为「先 limit 后 offset」，
     * 与 {@link com.rabbitc.ipage.sql.SqlStatement} 的绑定顺序一致。
     *
     * @param limit  行数
     * @param offset 偏移，可为 {@code null}
     * @return 子句，例如 {@code " LIMIT ? OFFSET ?"}
     */
    default String limitClause(int limit, Integer offset) {
        return offset == null
                ? " LIMIT ?"
                : " LIMIT ? OFFSET ?";
    }

    /**
     * 主键列的类型声明片段。
     *
     * @param javaType 主键的 Java 类型
     * @param autoIncrement 是否自增
     * @return 类型片段，例如 {@code "SERIAL PRIMARY KEY"} 或 {@code "BIGINT AUTO_INCREMENT PRIMARY KEY"}
     */
    String idColumnType(Class<?> javaType, boolean autoIncrement);

    /**
     * Java 类型映射为列类型。
     *
     * @param javaType Java 类型
     * @return 列类型，例如 {@code VARCHAR(255)}
     */
    String columnType(Class<?> javaType);

    /**
     * 是否支持在一条语句里用 {@code CREATE TABLE IF NOT EXISTS}。
     *
     * @return 支持返回 {@code true}
     */
    default boolean supportsIfNotExists() {
        return true;
    }

    /**
     * 建表语句的表名部分。
     *
     * @param table 表名
     * @return 片段
     */
    default String createTableHead(String table) {
        return supportsIfNotExists()
                ? "CREATE TABLE IF NOT EXISTS " + quote(table)
                : "CREATE TABLE " + quote(table);
    }

    /**
     * 查询表是否存在的语句。
     * <p>
     * 返回一行、一列即可：结果为 1 表示存在。
     *
     * @param table 表名
     * @return 查询语句
     */
    String tableExistsQuery(String table);

    /**
     * 查询某表已有列名的语句，按列名升序返回。
     *
     * @param table 表名
     * @return 查询语句
     */
    String columnsQuery(String table);
}
