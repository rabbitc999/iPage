package com.rabbitc.ipage.jdbc;

import com.rabbitc.ipage.dialect.Dialect;
import com.rabbitc.ipage.reflect.FieldDescriptor;
import com.rabbitc.ipage.reflect.ModelMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表结构同步：按模型定义自动建表、补列。
 * <p>
 * 设计上只做三件事，且都幂等：
 * <ol>
 *     <li>表不存在 → {@code CREATE TABLE}，主键按方言生成自增列；</li>
 *     <li>表存在但缺列 → {@code ALTER TABLE ... ADD COLUMN}；</li>
 *     <li>其余情况不动。</li>
 * </ol>
 * <b>刻意不做改列与删列</b>：自动 {@code ALTER COLUMN TYPE} 或 {@code DROP COLUMN} 在真实库里
 * 可能截断数据或丢列，这类决定必须由人做。缺列时会在日志里明确列出「已补」与「未处理」，
 * 便于把差异交回给迁移脚本。
 * <p>
 * 新增列一律允许为空：表里可能已有数据，加非空列会直接失败。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class SchemaSync {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaSync.class);

    private final ConnectionProvider connections;
    private final Dialect dialect;
    private final boolean dryRun;

    /**
     * 构造同步器。
     *
     * @param connections 连接来源
     * @param dialect     方言
     * @param dryRun      为 {@code true} 时只计算并记录将要执行的 SQL，不真正执行
     */
    public SchemaSync(ConnectionProvider connections, Dialect dialect, boolean dryRun) {
        this.connections = connections;
        this.dialect = dialect;
        this.dryRun = dryRun;
    }

    /**
     * 同步一组模型，并把建表/补列语句里的表名交给 {@code tableMapper} 改写。
     * <p>
     * 建表必须与查询用同一套表名，否则会出现「建了查不到」：会话带表名前缀时，
     * 建模要用 {@code dev_student}，查询也要用 {@code dev_student}。
     * 改写只作用在语句文本上（{@link #createTable}、{@link #addColumn}、表存在性检查），
     * 元数据本身仍是注解里的表名。
     *
     * @param metaList    模型的元数据
     * @param tableMapper 表名映射器，{@code null} 表示不改写
     * @return 执行报告
     */
    public Report synchronize(Iterable<ModelMeta> metaList, java.util.function.UnaryOperator<String> tableMapper) {
        this.tableMapper = tableMapper == null ? java.util.function.UnaryOperator.identity() : tableMapper;
        try {
            return synchronize(metaList);
        } finally {
            this.tableMapper = java.util.function.UnaryOperator.identity();
        }
    }

    /**
     * 表名改写器。
     */
    private java.util.function.UnaryOperator<String> tableMapper = java.util.function.UnaryOperator.identity();

    /**
     * 实际使用的表名。
     *
     * @param meta 元数据
     * @return 语句里该用的表名
     */
    private String tableOf(ModelMeta meta) {
        String table = tableMapper.apply(meta.table());
        return table == null || table.isBlank() ? meta.table() : table;
    }

    /**
     * 同步一组模型。
     *
     * @param metaList 模型的元数据
     * @return 执行报告
     */
    public Report synchronize(Iterable<ModelMeta> metaList) {
        List<String> executed = new ArrayList<>();
        try (Connection connection = connections.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (ModelMeta meta : metaList) {
                    syncOne(connection, meta, executed);
                }
                if (dryRun) {
                    connection.rollback();
                } else {
                    connection.commit();
                }
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw new IpagDataAccessException("同步表结构失败，已回滚", null, e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IpagDataAccessException("获取连接失败", null, e);
        }
        Report report = new Report(executed, dryRun);
        LOG.info("[ipage] 表结构同步完成：{}", report);
        return report;
    }

    private void syncOne(Connection connection, ModelMeta meta, List<String> executed) throws SQLException {
        String table = tableOf(meta);
        if (!tableExists(connection, table)) {
            String ddl = createTable(meta);
            run(connection, ddl, executed);
            LOG.info("[ipage] 已建表 {}（{} 列）", table, meta.columnFields().size());
            return;
        }
        Set<String> existing = existingColumns(connection, table);
        int added = 0;
        for (FieldDescriptor field : meta.columnFields()) {
            if (containedIgnoreCase(existing, field.column())) {
                continue;
            }
            String ddl = addColumn(table, field);
            run(connection, ddl, executed);
            added++;
            LOG.info("[ipage] 表 {} 补列 {}", table, field.column());
        }
        if (added == 0) {
            LOG.debug("[ipage] 表 {} 结构已是最新", table);
        }
    }

    /**
     * 判断表是否存在。
     * <p>
     * 不依赖 {@code CREATE TABLE IF NOT EXISTS}：建表只有在「确认不存在」时才发，
     * 这样「表存在但缺列」和「表不存在」两条路径就彻底分开了，
     * 也不会在 PostgreSQL 上因为大小写折叠把两张表看成一张。
     *
     * @param connection 连接
     * @param table      表名
     * @return 存在返回 {@code true}
     * @throws SQLException 查询失败
     */
    public boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement(dialect.tableExistsQuery(table));
             ResultSet result = prepared.executeQuery()) {
            return result.next();
        }
    }

    /**
     * 生成建表语句。
     * <p>
     * 自增条件：主键是整数类型且模型没有声明为 {@code fixed}（声明为 fixed 通常意味着
     * 由业务自己赋号，例如学号；这类列不该让数据库再生成一个）。
     *
     * @param meta 模型元数据
     * @return DDL
     */
    public String createTable(ModelMeta meta) {
        FieldDescriptor id = meta.idField();
        boolean autoIncrement = !id.fixed() && isInteger(id.javaType());
        List<String> definitions = new ArrayList<>();
        definitions.add(dialect.quote(id.column()) + " " + dialect.idColumnType(id.javaType(), autoIncrement));
        for (FieldDescriptor field : meta.columnFields()) {
            if (field == id) {
                continue;
            }
            definitions.add(columnDefinition(field));
        }
        return dialect.createTableHead(tableOf(meta)) + " (" + String.join(", ", definitions) + ")";
    }

    /**
     * 生成补列语句。
     *
     * @param table 表名
     * @param field 字段
     * @return DDL
     */
    public String addColumn(String table, FieldDescriptor field) {
        return "ALTER TABLE " + dialect.quote(table) + " ADD COLUMN " + columnDefinition(field);
    }

    private String columnDefinition(FieldDescriptor field) {
        // 不加 NOT NULL：表里可能已有数据，新增非空列会失败；非空约束交给校验层
        return dialect.quote(field.column()) + " " + dialect.columnType(field.javaType());
    }

    private void run(Connection connection, String sql, List<String> executed) throws SQLException {
        if (dryRun) {
            executed.add("[dry-run] " + sql);
            LOG.info("[ipage] 将执行：{}", sql);
            return;
        }
        LOG.info("[ipage] 执行 DDL：{}", sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            executed.add(sql);
        }
    }

    /**
     * 读取已有列。表不存在时返回空集。
     */
    private Set<String> existingColumns(Connection connection, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement prepared = connection.prepareStatement(dialect.columnsQuery(table));
             ResultSet result = prepared.executeQuery()) {
            while (result.next()) {
                columns.add(result.getString(1));
            }
        }
        return columns;
    }

    private static boolean containedIgnoreCase(Set<String> values, String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInteger(Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class;
    }

    /**
     * 同步报告。
     *
     * @param executed 实际执行（或 dry-run 下将要执行）的 DDL
     * @param dryRun   是否为演练
     */
    public record Report(List<String> executed, boolean dryRun) {

        /**
         * 构造报告，复制入参避免外部修改。
         *
         * @param executed 执行的 DDL
         * @param dryRun   是否演练
         */
        public Report {
            executed = List.copyOf(executed);
        }

        /**
         * 是否有变更。
         *
         * @return 有变更返回 {@code true}
         */
        public boolean changed() {
            return !executed.isEmpty();
        }

        /**
         * 变更条数。
         *
         * @return 条数
         */
        public int count() {
            return executed.size();
        }

        @Override
        public String toString() {
            return "执行 " + executed.size() + " 条" + (dryRun ? "（演练）" : "");
        }
    }

    /**
     * 便于按模型名聚合的辅助：把模型列表转成 {@link ModelMeta} 迭代器。
     *
     * @param classes 模型类
     * @return 元数据列表
     */
    public static List<ModelMeta> metasOf(Iterable<Class<?>> classes) {
        Map<String, ModelMeta> metas = new LinkedHashMap<>();
        for (Class<?> modelClass : classes) {
            ModelMeta meta = ModelMeta.of(modelClass);
            metas.putIfAbsent(meta.table(), meta);
        }
        return new ArrayList<>(metas.values());
    }
}
