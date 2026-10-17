package com.rabbitc.ipage.jdbc;

import com.rabbitc.ipage.dialect.Dialect;
import com.rabbitc.ipage.dialect.Dialects;
import com.rabbitc.ipage.enums.CheckerType;
import com.rabbitc.ipage.enums.OperatorType;
import com.rabbitc.ipage.exception.CheckerException;
import com.rabbitc.ipage.exception.ModelDefinitionException;
import com.rabbitc.ipage.reflect.FieldDescriptor;
import com.rabbitc.ipage.reflect.ModelMeta;
import com.rabbitc.ipage.reflect.ModelValidator;
import com.rabbitc.ipage.reflect.ValueConverter;
import com.rabbitc.ipage.sql.Query;
import com.rabbitc.ipage.sql.SqlCondition;
import com.rabbitc.ipage.sql.SqlStatement;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型会话：SQL 引擎的对外门面。
 * <p>
 * 一个会话对应一份数据库访问上下文；连接按需借用、用完即还，会话本身不持有连接，
 * 因此可以在多个线程里共享（各用各的连接）。它把三件事分清了：
 * <ul>
 *     <li><b>元数据</b>：{@link ModelMeta} 解析一次全局缓存，构造会话的开销可以忽略；</li>
 *     <li><b>SQL</b>：全部由 {@link Query} + {@link SqlStatement} 生成，参数一律占位符；</li>
 *     <li><b>方言</b>：可以显式指定，也可以交给 {@link #sql(ConnectionProvider)} 自动探测。</li>
 * </ul>
 * 会话提供四类能力：
 * <ol>
 *     <li><b>执行 SQL</b>：{@link #query}、{@link #queryScalar}、{@link #update(SqlStatement)}；</li>
 *     <li><b>读写模型</b>：{@link #selectList(Query)}、{@link #insert}、{@link #update(Object)}、{@link #delete}；</li>
 *     <li><b>同步表结构</b>：{@link #synchronizeSchema}；</li>
 *     <li><b>事务</b>：{@link #transaction}，事务内复用同一个连接。</li>
 * </ol>
 * 本类不依赖 Spring、Servlet 或任何连接池，运行时只用到 JDK 与 SLF4J 门面。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class ModelSession {

    private static final Logger LOG = LoggerFactory.getLogger(ModelSession.class);

    /**
     * 表名前缀只接受字母、数字与下划线，避免配置项成为注入点。
     */
    private static final Pattern SAFE_PREFIX = Pattern.compile("^[A-Za-z0-9_]*$");

    /**
     * 显示模板里的字段占位，与 {@link com.rabbitc.ipage.reflect.TemplateRenderer} 的写法保持一致。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * 批量删除时 {@code IN} 列表的每批长度：PostgreSQL 的绑定参数上限是 65535，留足余量。
     */
    private static final int IN_BATCH = 1000;

    private final ConnectionProvider connections;
    private final String tablePrefix;
    private final Dialect dialect;
    private final boolean validating;

    /**
     * 构造会话，方言取 {@link Dialect#DEFAULT}，写入前执行校验。
     *
     * @param connections 连接来源
     */
    public ModelSession(ConnectionProvider connections) {
        this(connections, "", Dialect.DEFAULT, true);
    }

    /**
     * 构造会话。
     *
     * @param connections 连接来源
     * @param dialect     方言，决定分页语法与建表类型
     */
    public ModelSession(ConnectionProvider connections, Dialect dialect) {
        this(connections, "", dialect, true);
    }

    /**
     * 构造会话。
     *
     * @param connections 连接来源
     * @param tablePrefix 表名前缀，例如多套环境共库时的 {@code dev_}，可为空
     */
    public ModelSession(ConnectionProvider connections, String tablePrefix) {
        this(connections, tablePrefix, Dialect.DEFAULT, true);
    }

    /**
     * 构造会话。
     *
     * @param connections 连接来源
     * @param tablePrefix 表名前缀
     * @param dialect     方言
     */
    public ModelSession(ConnectionProvider connections, String tablePrefix, Dialect dialect) {
        this(connections, tablePrefix, dialect, true);
    }

    /**
     * 构造会话。
     *
     * @param connections 连接来源
     * @param tablePrefix 表名前缀
     * @param dialect     方言
     * @param validating  写入模型前是否执行注解校验；{@code false} 时 {@link #insert} 与
     *                    {@link #update(Object)} 不校验（适合已由上游清洗过的批量导入）
     */
    public ModelSession(ConnectionProvider connections, String tablePrefix, Dialect dialect, boolean validating) {
        if (connections == null) {
            throw new IllegalArgumentException("连接来源不能为空");
        }
        String prefix = tablePrefix == null ? "" : tablePrefix.trim();
        if (!SAFE_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("表名前缀只能包含字母、数字与下划线，实际为「" + prefix + "」");
        }
        this.connections = connections;
        this.tablePrefix = prefix;
        this.dialect = dialect == null ? Dialect.DEFAULT : dialect;
        this.validating = validating;
    }

    /**
     * 最小用法：给一个连接来源，方言自动探测。
     * <p>
     * 探测需要一次真实连接，所以本方法会立刻连库；连不上直接抛异常——
     * 「配置错了」应该在启动期暴露，而不是等到第一条查询。
     *
     * @param connections 连接来源
     * @return 会话
     */
    public static ModelSession sql(ConnectionProvider connections) {
        return sql(connections, "");
    }

    /**
     * 最小用法：自动探测方言，并指定表名前缀。
     *
     * @param connections 连接来源
     * @param tablePrefix 表名前缀，可为空
     * @return 会话
     */
    public static ModelSession sql(ConnectionProvider connections, String tablePrefix) {
        return new ModelSession(connections, tablePrefix, detectDialect(connections), true);
    }

    /**
     * 最小用法：给 URL 与账号密码，方言自动探测。不需要连接池，也不依赖 Spring 的数据源。
     *
     * @param url      JDBC URL，例如 {@code jdbc:postgresql://127.0.0.1:5432/school}
     * @param user     用户名，可为 {@code null}
     * @param password 密码，可为 {@code null}
     * @return 会话
     */
    public static ModelSession sql(String url, String user, String password) {
        return sql(ConnectionProvider.of(url, user, password));
    }

    /**
     * 借一个连接探测方言。
     * <p>
     * 只探测一次并把结果固化到会话上：同一份配置不会在运行期突然换库。
     *
     * @param connections 连接来源
     * @return 方言
     */
    private static Dialect detectDialect(ConnectionProvider connections) {
        try (Connection connection = connections.getConnection()) {
            Dialect detected = Dialects.detect(connection);
            LOG.debug("[ipage] 方言探测结果：{}", detected.name());
            return detected;
        } catch (SQLException e) {
            throw new IpagDataAccessException("探测数据库方言失败，请检查连接配置", null, e);
        }
    }

    // ------------------------------------------------------------------ 基本信息

    /**
     * 方言。
     *
     * @return 方言
     */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * 表名前缀。
     *
     * @return 前缀，未配置时为空串
     */
    public String tablePrefix() {
        return tablePrefix;
    }

    /**
     * 某个模型在当前会话下实际使用的表名（已带前缀）。
     * <p>
     * 手写 SQL 时用它拼表名，才能与框架按模型生成的语句落到同一张表。
     * 表名来自注解，不是请求参数；若仍需嵌进外部输入，请自行校验。
     *
     * @param modelClass 模型类
     * @return 实际表名
     */
    public String tableName(Class<?> modelClass) {
        return metaOf(modelClass).table();
    }

    /**
     * 是否在写入前执行注解校验。
     *
     * @return 启用返回 {@code true}
     */
    public boolean validating() {
        return validating;
    }

    // ------------------------------------------------------------------ 同步表结构

    /**
     * 按模型定义同步表结构（建表、补列）。
     * <p>
     * 只做加法：不存在的表建出来，缺的列补上；改类型、删列一律不动。
     *
     * @param modelClasses 模型类
     * @return 同步报告
     */
    public SchemaSync.Report synchronizeSchema(Class<?>... modelClasses) {
        return synchronizeSchema(false, modelClasses);
    }

    /**
     * 按模型定义同步表结构。
     *
     * @param dryRun       为 {@code true} 时只打印将执行的 DDL，不真正执行
     * @param modelClasses 模型类
     * @return 同步报告
     */
    public SchemaSync.Report synchronizeSchema(boolean dryRun, Class<?>... modelClasses) {
        return new SchemaSync(connections, dialect, dryRun)
                .synchronize(SchemaSync.metasOf(java.util.Arrays.asList(modelClasses)), this::addPrefix);
    }

    // ------------------------------------------------------------------ 执行 SQL

    /**
     * 执行查询，逐行交给映射器。
     * <p>
     * 这是最底层也最通用的出口：{@link #selectList} 之类的方法自己也是走它。
     * 参数按 {@link SqlStatement#parameters()} 的顺序绑定。
     * <p>
     * 注意：这条通道不会替你改写表名。会话配了表名前缀时，按模型走的查询已经把前缀
     * 放进元数据里了；手写 SQL 需要用 {@link #tableName(Class)} 取实际表名再拼进去。
     *
     * @param statement 语句
     * @param mapper    行映射器
     * @param <T>       行类型
     * @return 行列表
     */
    public <T> List<T> query(SqlStatement statement, RowMapper<T> mapper) {
        String sql = statement.sql();
        try (Scoped scope = prepare(sql);
             ResultSet result = query(scope.statement, statement.parameters())) {
            List<T> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(mapper.mapRow(result));
            }
            return rows;
        } catch (SQLException e) {
            throw failure("查询失败", sql, e);
        }
    }

    /**
     * 执行查询，只取第一列第一行。
     *
     * @param statement 语句
     * @return 值，无结果或结果为 {@code NULL} 时返回 {@code null}
     */
    public Object queryScalar(SqlStatement statement) {
        String sql = statement.sql();
        try (Scoped scope = prepare(sql);
             ResultSet result = query(scope.statement, statement.parameters())) {
            return result.next() ? result.getObject(1) : null;
        } catch (SQLException e) {
            throw failure("查询失败", sql, e);
        }
    }

    /**
     * 执行 INSERT / UPDATE / DELETE 等写操作。
     *
     * @param statement 语句
     * @return 受影响行数
     */
    public int update(SqlStatement statement) {
        return execute(statement, "执行更新失败");
    }

    // ------------------------------------------------------------------ 事务

    /**
     * 在一个事务里做一组操作。
     * <p>
     * 期间通过本会话发起的调用都拿到同一个连接、加入同一个事务：工作单元正常返回则提交，
     * 抛异常则回滚并把异常抛出去（受检异常包装成 {@link IpagDataAccessException}）。
     * <p>
     * 约束：事务只在<b>当前线程</b>可见，并且要求操作都走同一个会话。
     * 事务内另起线程、或换一个会话操作，都不在事务保护范围内——这与 JDBC 的语义一致，
     * 框架不假装自己支持分布式事务。嵌套调用会并入外层事务，由最外层决定成败。
     *
     * @param work 工作单元
     */
    public void transaction(Transactional work) {
        if (Transaction.currentFor(this) != null) {
            invokeIn(this, work);
            return;
        }
        try (Connection connection = connections.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            // 作用域本身不参与提交/回滚判断，只负责登记与撤销，因此不开 try-with-resources：
            // 显式 close 便于把「回滚 → 恢复 autoCommit → 撤销登记」的顺序写清楚。
            Transaction transaction = Transaction.open(this, connection);
            try {
                invokeIn(this, work);
                connection.commit();
            } catch (RuntimeException | Error e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                transaction.close();
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException e) {
            throw new IpagDataAccessException("开启事务失败", null, e);
        }
    }

    /**
     * 事务工作单元。
     */
    @FunctionalInterface
    public interface Transactional {

        /**
         * 执行工作。
         *
         * @param session 当前会话
         * @throws Exception 任何异常都会导致回滚
         */
        void run(ModelSession session) throws Exception;
    }

    private static void invokeIn(ModelSession session, Transactional work) {
        try {
            work.run(session);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IpagDataAccessException("事务内的操作失败，已回滚", null, e);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.warn("[ipage] 回滚失败，连接可能已断开：{}", e.getMessage());
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            LOG.warn("[ipage] 恢复 autoCommit={} 失败：{}", autoCommit, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 按查询对象取一页数据，同时返回总数。
     *
     * @param query  查询
     * @param mapper 行映射器
     * @param page   页码，从 1 开始
     * @param size   每页行数
     * @param <T>    模型类型
     * @return 分页结果
     */
    public <T> Page<T> selectPage(Query query, RowMapper<T> mapper, int page, int size) {
        int pageNumber = Math.max(page, 1);
        int pageSize = Math.max(size, 1);
        query.page(pageNumber, pageSize);

        long total = count(query);
        if (total == 0) {
            return new Page<>(List.of(), 0, pageNumber, pageSize);
        }
        return new Page<>(selectList(query, mapper), total, pageNumber, pageSize);
    }

    /**
     * 按查询对象取一页实体，行映射按查询已登记的选择项自动生成。
     *
     * @param query 查询
     * @param page  页码，从 1 开始
     * @param size  每页行数
     * @param <T>   模型类型
     * @return 分页结果
     */
    public <T> Page<T> selectPage(Query query, int page, int size) {
        return selectPage(query, RowMapper.of(query), page, size);
    }

    /**
     * 按查询对象取列表，自动套用查询上的排序与分页。
     *
     * @param query  查询
     * @param mapper 行映射器
     * @param <T>    模型类型
     * @return 行列表
     */
    public <T> List<T> selectList(Query query, RowMapper<T> mapper) {
        return query(SqlStatement.select(query, true, dialect), mapper);
    }

    /**
     * 按查询对象取实体列表。
     *
     * @param query 查询
     * @param <T>   模型类型
     * @return 实体列表
     */
    public <T> List<T> selectList(Query query) {
        return selectList(query, RowMapper.of(query));
    }

    /**
     * 按查询对象取第一条。
     * <p>
     * 内部把分页临时改成 {@code LIMIT 1}，取完后把查询对象上的分页<b>还原</b>成调用前的样子，
     * 免得调用方复用一个查询对象时被悄悄改掉页码。
     *
     * @param query  查询
     * @param mapper 行映射器
     * @param <T>    模型类型
     * @return 结果，无匹配时为空
     */
    public <T> Optional<T> selectOne(Query query, RowMapper<T> mapper) {
        Integer savedLimit = query.limit();
        Integer savedOffset = query.offset();
        List<T> rows;
        try {
            query.page(1, 1);
            rows = selectList(query, mapper);
        } finally {
            query.limit(savedLimit, savedOffset);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 按查询对象取第一个实体。
     *
     * @param query 查询
     * @param <T>   模型类型
     * @return 实体，无匹配时为空
     */
    public <T> Optional<T> selectOne(Query query) {
        return selectOne(query, RowMapper.of(query));
    }

    /**
     * 按主键取一个模型。
     *
     * @param modelClass 模型类
     * @param id         主键值
     * @param <T>        模型类型
     * @return 模型，不存在时为空
     */
    public <T> Optional<T> findById(Class<T> modelClass, Object id) {
        ModelMeta meta = metaOf(modelClass);
        Query query = Query.of(meta, dialect);
        SqlStatement statement = SqlStatement.selectById(query, id, dialect);
        List<T> rows = query(statement, RowMapper.of(query));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 统计符合条件的行数。
     *
     * @param query 查询
     * @return 行数
     */
    public long count(Query query) {
        Object value = queryScalar(SqlStatement.count(query, dialect));
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 判断是否存在符合条件的行。
     *
     * @param query 查询
     * @return 存在则 {@code true}
     */
    public boolean exists(Query query) {
        return count(query) > 0;
    }

    /**
     * 读取某字段的可选值，用于下拉框。
     * <p>
     * 标签列的取法：模型声明了 {@code @ModelClass.text} 时用其中的第一个 {@code ${字段}}
     * （模板本身是给显示用的，这里只取能放进 SELECT 的那一项）；
     * 否则退化为第一个文本型字段；再不行就按主键显示。
     *
     * @param modelClass 模型类
     * @return 主键到显示文本的有序映射
     */
    public Map<String, String> options(Class<?> modelClass) {
        ModelMeta meta = metaOf(modelClass);
        String labelColumn = labelColumn(meta);
        SqlStatement statement = SqlStatement.options(meta, labelColumn,
                dialect.quote(meta.table()) + "." + dialect.quote(labelColumn), dialect);
        String sql = statement.sql();
        Map<String, String> options = new LinkedHashMap<>();
        try (Scoped scope = prepare(sql);
             ResultSet result = query(scope.statement, statement.parameters())) {
            while (result.next()) {
                Object id = result.getObject(1);
                Object label = result.getObject(2);
                if (id != null) {
                    options.put(String.valueOf(id), label == null ? "" : String.valueOf(label));
                }
            }
            return options;
        } catch (SQLException e) {
            throw failure("读取下拉选项失败：" + meta.modelClass().getSimpleName(), sql, e);
        }
    }

    /**
     * 下拉框的标签列。
     *
     * @param meta 元数据
     * @return 列名
     */
    private static String labelColumn(ModelMeta meta) {
        String template = meta.displayTemplate();
        if (template != null && !template.isBlank()) {
            Matcher matcher = PLACEHOLDER.matcher(template);
            while (matcher.find()) {
                String name = matcher.group(1).trim();
                int dot = name.indexOf('.');
                Optional<FieldDescriptor> field = meta.field(dot < 0 ? name : name.substring(0, dot));
                if (field.isPresent() && field.get().isColumn()) {
                    return field.get().column();
                }
            }
        }
        for (FieldDescriptor field : meta.columnFields()) {
            if (field.javaType() == String.class && field != meta.idField()) {
                return field.column();
            }
        }
        return meta.idColumn();
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 新增一条记录。
     * <p>
     * 主键为空时交给数据库自增，并把生成的主键回填到实体上。
     * 会话启用校验时，先按 {@link CheckerType#ADD} 执行注解校验。
     *
     * @param entity 实体
     * @param <T>    模型类型
     * @return 受影响行数
     * @throws CheckerException 校验不通过
     */
    public <T> int insert(T entity) throws CheckerException {
        return insert(entity, CheckerType.ADD, null);
    }

    /**
     * 新增一条记录。
     *
     * @param entity    实体
     * @param operation 校验场景，{@code null} 时按 {@link CheckerType#ADD}
     * @param before    修改前的快照，供 {@code checker(self, before, type)} 使用，新增时传 {@code null}
     * @param <T>       模型类型
     * @return 受影响行数
     * @throws CheckerException 校验不通过
     */
    public <T> int insert(T entity, CheckerType operation, Object before) throws CheckerException {
        ModelMeta meta = metaOf(entity.getClass());
        if (validating) {
            ModelValidator.validate(entity, operation == null ? CheckerType.ADD : operation, before);
        }
        Map<String, Object> values = writableValues(meta, entity, true);
        SqlStatement statement = SqlStatement.insert(meta, values, dialect);
        String sql = statement.sql();
        boolean needKeys = meta.idValue(entity) == null && isInteger(meta.idJavaType());
        try (Scoped scope = prepare(sql, needKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS)) {
            bind(scope.statement, statement.parameters());
            int affected = scope.statement.executeUpdate();
            if (needKeys && affected > 0) {
                try (ResultSet keys = scope.statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        Object generated = keys.getObject(1);
                        if (generated != null) {
                            meta.idField().write(entity, ValueConverter.fromDatabase(meta.idJavaType(), generated));
                        }
                    }
                }
            }
            return affected;
        } catch (SQLException e) {
            throw failure("新增 " + meta.name() + " 失败", sql, e);
        }
    }

    /**
     * 按主键更新一条记录，主键字段本身不会被写回。
     * <p>
     * 校验时会先按主键把旧记录读出来作为 {@code before} 快照，
     * 这样模型类上的 {@code checker(self, before, type)} 能拿到「改之前是什么」。
     *
     * @param entity 实体
     * @param <T>    模型类型
     * @return 受影响行数
     * @throws CheckerException 校验不通过
     */
    public <T> int update(T entity) throws CheckerException {
        ModelMeta meta = metaOf(entity.getClass());
        Object id = meta.idValue(entity);
        if (id == null) {
            throw new ModelDefinitionException("更新 " + meta.name() + " 时主键为空，无法定位记录");
        }
        if (validating) {
            Object before = findById(entity.getClass(), id).orElse(null);
            ModelValidator.validate(entity, CheckerType.UPDATE, before);
        }
        Map<String, Object> values = writableValues(meta, entity, false);
        if (values.isEmpty()) {
            return 0;
        }
        return execute(SqlStatement.update(meta, values, meta.idColumn(), id, dialect),
                "更新 " + meta.name() + " 失败");
    }

    /**
     * 按主键删除。
     *
     * @param modelClass 模型类
     * @param id         主键值
     * @return 受影响行数
     */
    public int deleteById(Class<?> modelClass, Object id) {
        ModelMeta meta = metaOf(modelClass);
        return execute(SqlStatement.delete(meta, meta.idColumn(), id, dialect),
                "删除 " + meta.name() + " 失败");
    }

    /**
     * 按主键删除，与 {@link #deleteById(Class, Object)} 等价，便于按实体名读。
     *
     * @param modelClass 模型类
     * @param id         主键值
     * @return 受影响行数
     */
    public int delete(Class<?> modelClass, Object id) {
        return deleteById(modelClass, id);
    }

    /**
     * 删除一个实体（按主键）。
     *
     * @param entity 实体
     * @param <T>    模型类型
     * @return 受影响行数
     */
    public <T> int delete(T entity) {
        ModelMeta meta = metaOf(entity.getClass());
        Object id = meta.idValue(entity);
        if (id == null) {
            throw new ModelDefinitionException("删除 " + meta.name() + " 时主键为空，无法定位记录");
        }
        return deleteById(entity.getClass(), id);
    }

    /**
     * 按主键批量删除。
     *
     * @param modelClass 模型类
     * @param ids        主键值集合；为空时不做任何事
     * @return 受影响行数
     */
    public int deleteByIds(Class<?> modelClass, Iterable<?> ids) {
        ModelMeta meta = metaOf(modelClass);
        Set<Object> keys = new LinkedHashSet<>();
        ids.forEach(keys::add);
        if (keys.isEmpty()) {
            return 0;
        }
        int affected = 0;
        List<Object> batch = new ArrayList<>(IN_BATCH);
        for (Object key : keys) {
            batch.add(key);
            if (batch.size() == IN_BATCH) {
                affected += deleteBatch(meta, batch);
                batch = new ArrayList<>(IN_BATCH);
            }
        }
        if (!batch.isEmpty()) {
            affected += deleteBatch(meta, batch);
        }
        return affected;
    }

    private int deleteBatch(ModelMeta meta, List<Object> keys) {
        SqlCondition condition = new SqlCondition(meta.idColumn(), OperatorType.IN, keys);
        return execute(SqlStatement.deleteWhere(meta, List.of(condition), dialect),
                "批量删除 " + meta.name() + " 失败");
    }

    // ------------------------------------------------------------------ 内部实现

    private int execute(SqlStatement statement, String message) {
        String sql = statement.sql();
        try (Scoped scope = prepare(sql)) {
            bind(scope.statement, statement.parameters());
            return scope.statement.executeUpdate();
        } catch (SQLException e) {
            throw failure(message, sql, e);
        }
    }

    private static IpagDataAccessException failure(String message, String sql, SQLException e) {
        LOG.debug("[ipage] {} | SQL: {} | 原因：{}", message, sql, e.getMessage());
        return new IpagDataAccessException(message, sql, e);
    }

    private static boolean isInteger(Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class;
    }

    private static void bind(PreparedStatement prepared, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Object value = parameters.get(i);
            if (value instanceof java.time.LocalDate date) {
                prepared.setObject(i + 1, java.sql.Date.valueOf(date));
            } else if (value instanceof java.time.LocalDateTime dateTime) {
                prepared.setObject(i + 1, java.sql.Timestamp.valueOf(dateTime));
            } else {
                prepared.setObject(i + 1, value);
            }
        }
    }

    private static ResultSet query(PreparedStatement prepared, List<Object> parameters) throws SQLException {
        bind(prepared, parameters);
        return prepared.executeQuery();
    }

    /**
     * 取模型元数据。
     * <p>
     * 带表名前缀时，取的是「表名已换成实际物理表名」的那份元数据：建表、查询、写入都从
     * {@link ModelMeta#table()} 取表名，因此三条路径天然用同一个名字，不需要在 SQL 文本上打补丁。
     * 元数据按「类 + 实际表名」缓存，{@code dev_} 与 {@code test_} 各一份，互不覆盖。
     *
     * @param modelClass 模型类
     * @return 元数据
     */
    private ModelMeta metaOf(Class<?> modelClass) {
        return ModelMeta.of(this::addPrefix, modelClass);
    }

    /**
     * 给表名加上会话前缀。
     *
     * @param table 注解里的表名
     * @return 实际使用的表名
     */
    private String addPrefix(String table) {
        return tablePrefix.isEmpty() ? table : tablePrefix + table;
    }

    /**
     * 收集可写入的列值。{@code fixed} 字段与聚合字段不参与写入。
     *
     * @param meta      元数据
     * @param entity    实体
     * @param inserting 是否新增；新增时主键为空则不写入，交给自增
     * @return 列名到值的映射，保持字段声明顺序
     */
    private static Map<String, Object> writableValues(ModelMeta meta, Object entity, boolean inserting) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldDescriptor field : meta.columnFields()) {
            if (field.fixed()) {
                continue;
            }
            Object value = field.read(entity);
            if (inserting && field == meta.idField() && ValueConverter.isBlank(value)) {
                continue;
            }
            values.put(field.column(), ValueConverter.toDatabase(value));
        }
        return values;
    }

    /**
     * 开一条语句：能加入当前线程上本会话的显式事务就用事务连接，否则借一个连接独占使用。
     *
     * @param sql           SQL
     * @param generatedKeys 是否需要回读自增主键
     * @return 语句作用域，关闭时归还独占的连接
     * @throws SQLException 准备语句失败
     */
    private Scoped prepare(String sql, int generatedKeys) throws SQLException {
        Transaction current = Transaction.currentFor(this);
        if (current != null) {
            return new Scoped(current.prepare(sql, generatedKeys), null);
        }
        Connection connection = connections.getConnection();
        try {
            return new Scoped(connection.prepareStatement(sql, generatedKeys), connection);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private Scoped prepare(String sql) throws SQLException {
        return prepare(sql, Statement.NO_GENERATED_KEYS);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.warn("[ipage] 关闭资源失败：{}", e.getMessage());
        }
    }

    /**
     * 语句作用域：独占模式下连带关闭连接，事务模式下只关闭语句。
     */
    private static final class Scoped implements AutoCloseable {

        private final PreparedStatement statement;
        private final Connection owned;

        private Scoped(PreparedStatement statement, Connection owned) {
            this.statement = statement;
            this.owned = owned;
        }

        @Override
        public void close() {
            closeQuietly(statement);
            if (owned != null) {
                closeQuietly(owned);
            }
        }
    }

    /**
     * 显式事务在当前线程上的作用域。
     * <p>
     * 用 {@link ThreadLocal} 而不是把连接挂到会话字段上：会话可以被多个线程共享，
     * 而事务天然是线程私有的；两者混在一起会串事务。
     */
    private static final class Transaction implements AutoCloseable {

        private static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();

        private final ModelSession owner;
        private final Connection connection;

        private Transaction(ModelSession owner, Connection connection) {
            this.owner = owner;
            this.connection = connection;
        }

        private static Transaction currentFor(ModelSession session) {
            Transaction current = CURRENT.get();
            return current != null && current.owner == session ? current : null;
        }

        private static Transaction open(ModelSession owner, Connection connection) {
            Transaction transaction = new Transaction(owner, connection);
            CURRENT.set(transaction);
            return transaction;
        }

        private PreparedStatement prepare(String sql, int generatedKeys) throws SQLException {
            return connection.prepareStatement(sql, generatedKeys);
        }

        @Override
        public void close() {
            CURRENT.remove();
        }
    }
}
