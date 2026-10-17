package com.rabbitc.ipage.sql;

import com.rabbitc.ipage.dialect.Dialect;
import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.enums.OperatorType;
import com.rabbitc.ipage.enums.SortType;
import com.rabbitc.ipage.enums.SqlFunction;
import com.rabbitc.ipage.exception.ModelDefinitionException;
import com.rabbitc.ipage.reflect.ModelMeta;
import com.rabbitc.ipage.reflect.FieldDescriptor;
import com.rabbitc.ipage.reflect.ValueConverter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 动态查询描述：一次把「查哪张表、取哪些列、关联哪些表、按什么过滤」组装好。
 * <p>
 * 与旧实现的差别：
 * <ul>
 *     <li><b>别名唯一</b>：每张被 JOIN 的表都在全局别名校验下申请别名，同一张表不会再被 JOIN 两次；</li>
 *     <li><b>列名可解析</b>：条件、排序都按 Java 字段名或列名解析成「别名.列」，不再是裸列名；</li>
 *     <li><b>结果回填有据</b>：{@link SelectedField} 直接记录别名到字段路径，映射阶段不做字符串拆分；</li>
 *     <li><b>过滤条件封闭</b>：操作符来自枚举，值一律走占位符，不存在拼接注入面。</li>
 * </ul>
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */
public final class Query {

    private final ModelMeta meta;
    private final String baseAlias;
    private final Dialect dialect;

    private final List<SelectedField> selectedFields = new ArrayList<>();
    private final List<JoinCondition> joins = new ArrayList<>();
    private final Set<String> aliases = new LinkedHashSet<>();
    private final Set<String> joinedRelations = new LinkedHashSet<>();
    private final List<SqlCondition> whereConditions = new ArrayList<>();
    private final List<SqlCondition> havingConditions = new ArrayList<>();
    private final Map<String, SortType> orderBy = new LinkedHashMap<>();
    private final Set<String> groupBy = new LinkedHashSet<>();
    private final Map<String, String> aggregateExpressions = new LinkedHashMap<>();

    private Integer limit;
    private Integer offset;

    private Query(ModelMeta meta, Dialect dialect) {
        this.meta = meta;
        this.dialect = dialect;
        this.baseAlias = Aliases.of(meta.table());
        aliases.add(baseAlias);
        selectColumns();
        joinAssociations();
        selectAggregates();
    }

    /**
     * 为一个模型构建查询对象。
     *
     * @param modelClass 模型类
     * @return 查询对象
     */
    public static Query of(Class<?> modelClass) {
        return of(modelClass, Dialect.DEFAULT);
    }

    /**
     * 为一个模型构建查询对象，标识符按方言加引号。
     *
     * @param modelClass 模型类
     * @param dialect    方言
     * @return 查询对象
     */
    public static Query of(Class<?> modelClass, Dialect dialect) {
        return new Query(ModelMeta.of(modelClass), dialect);
    }

    /**
     * 为一个模型元数据构建查询对象。
     *
     * @param meta 元数据
     * @return 查询对象
     */
    public static Query of(ModelMeta meta) {
        return of(meta, Dialect.DEFAULT);
    }

    /**
     * 为一个模型元数据构建查询对象，标识符按方言加引号。
     *
     * @param meta    元数据
     * @param dialect 方言
     * @return 查询对象
     */
    public static Query of(ModelMeta meta, Dialect dialect) {
        return new Query(meta, dialect);
    }

    // ---------------------------------------------------------------- 组装

    private void selectColumns() {
        for (FieldDescriptor field : meta.columnFields()) {
            if (field.type() == FieldType.ASSOCIATION_MODEL) {
                continue;
            }
            selectedFields.add(SelectedField.main(
                    selectAlias(baseAlias + "_" + field.name()),
                    aliased(baseAlias, field.column()),
                    field.name(),
                    true));
        }
    }

    private void selectAggregates() {
        if (meta.aggregateFields().isEmpty()) {
            return;
        }
        for (FieldDescriptor field : meta.aggregateFields()) {
            String expr = aggregateExpressionOf(field);
            selectedFields.add(SelectedField.aggregate(field.name(), expr, field.name(), field.function()));
        }
        // 分组必须覆盖所有非聚合选择列：既包括本表的列，也包括关联表 JOIN 进来后被选出的列。
        // 只按主键分组会让 SELECT 里的「关联表列」成为非法引用（PostgreSQL 会直接报错）。
        // 主表主键在前，保证分组顺序稳定；主键相同的行在各关联表上取值唯一，所以不会多出行。
        groupBy.add(aliased(baseAlias, meta.idColumn()));
        for (SelectedField selected : selectedFields) {
            if (selected.kind() != SelectedField.Kind.AGGREGATE) {
                groupBy.add(selected.columnExpression());
            }
        }
    }

    /**
     * 取聚合表达式，按字段名缓存。
     * <p>
     * 同一个聚合字段可能被问两次（SELECT 一次、HAVING 一次）。若不缓存，
     * 第二次调用会在 {@code joins} 里再挂一遍 LEFT JOIN，SQL 就重复连接同一张表了。
     *
     * @param field 聚合字段
     * @return SQL 表达式
     */
    private String aggregateExpressionOf(FieldDescriptor field) {
        return aggregateExpressions.computeIfAbsent(field.name(), key -> aggregateExpression(field));
    }

    /**
     * 为关联字段建立 LEFT JOIN 并选出关联表的列。
     * <p>
     * 同一个关联类只连一次、只选一次列：一个 {@code ASSOCIATION_ID} 加一个
     * {@code ASSOCIATION_MODEL} 指向同一张表是常见写法，若各处理一遍就会产生重复列与重复 JOIN。
     * 选中列的 {@code entityPath} 指向 {@code ASSOCIATION_MODEL} 字段名（页面读的是那个对象），
     * 只有实体上没有对应对象字段时才回退到 {@code ASSOCIATION_ID} 字段名。
     * <p>
     * <b>连表条件优先用 {@code ASSOCIATION_ID} 字段</b>：它才代表本表上真实存在的外键列。
     * 只有该关联类没有 {@code ASSOCIATION_ID} 时，才退而使用 {@code ASSOCIATION_MODEL}，
     * 且要求它的 {@code column()} 在本表上确实是一列；否则说明那是「子表反查父表」的用法
     * （例如聚合统计），此时不该在这里连表，留给聚合表达式自己处理。
     */
    private void joinAssociations() {
        Set<Class<?>> handled = new LinkedHashSet<>();
        for (FieldDescriptor field : meta.fields()) {
            FieldType type = field.type();
            if (type != FieldType.ASSOCIATION_ID && type != FieldType.ASSOCIATION_MODEL) {
                continue;
            }
            if (field.relatedClass() == null || !handled.add(field.relatedClass())) {
                continue;
            }
            FieldDescriptor foreignKey = foreignKeyOf(field);
            if (foreignKey == null) {
                continue;
            }
            ModelMeta relation = ModelMeta.of(field.relatedClass());
            String relationAlias = aliasOf(relation.table());
            if (relationAlias == null) {
                relationAlias = requestAlias(relation.table());
                String on = aliased(baseAlias, foreignKey.column())
                            + " = " + aliased(relationAlias, relation.idColumn());
                joins.add(new JoinCondition(relation.table(), relationAlias, on));
                joinedRelations.add(relation.table());
            }
            String owner = associationOwner(field);
            for (FieldDescriptor relationField : relation.columnFields()) {
                selectedFields.add(SelectedField.association(
                        selectAlias(relationAlias + "_" + relationField.name()),
                        aliased(relationAlias, relationField.column()),
                        owner + "." + relationField.name(),
                        relation.modelClass()));
            }
        }
    }

    /**
     * 找可用于连表的外键字段。
     * <p>
     * 优先用 {@code ASSOCIATION_ID}——它才明确表示「本表上真实存在的外键列」。
     * 没有 {@code ASSOCIATION_ID} 时才看 {@code ASSOCIATION_MODEL}，并且要求它的
     * {@code column()} 在本表上确实是一个列；否则说明那是「子表反查父表」的用法
     * （例如聚合统计），不该在这里连表，留给聚合表达式自己处理。
     *
     * @param origin 关联字段
     * @return 外键字段，无法连表时为 {@code null}
     */
    private FieldDescriptor foreignKeyOf(FieldDescriptor origin) {
        for (FieldDescriptor field : meta.fields()) {
            if (field.type() == FieldType.ASSOCIATION_ID && field.relatedClass() == origin.relatedClass()) {
                return field;
            }
        }
        if (origin.type() == FieldType.ASSOCIATION_MODEL
            && meta.fieldByColumn(origin.column()).isPresent()) {
            return origin;
        }
        return null;
    }

    /**
     * 关联列在实体上的落点字段名：优先取同类的 {@code ASSOCIATION_MODEL} 字段。
     */
    private String associationOwner(FieldDescriptor origin) {
        for (FieldDescriptor field : meta.fields()) {
            if (field.type() == FieldType.ASSOCIATION_MODEL && field.relatedClass() == origin.relatedClass()) {
                return field.name();
            }
        }
        return origin.name();
    }

    private String requestAlias(String table) {
        String candidate = Aliases.of(table);
        String unique = candidate;
        int suffix = 1;
        while (aliases.contains(unique)) {
            unique = candidate + suffix++;
        }
        aliases.add(unique);
        return unique;
    }

    /**
     * 聚合表达式。
     * <p>
     * {@code useRelatedForeignKey = true} 时，聚合发生在「子表指向本表」的方向上：
     * 先按外键把子表连到当前查询（如果还没有连），再对子表列做聚合。
     *
     * @param field 聚合字段
     * @return SQL 表达式
     */
    private String aggregateExpression(FieldDescriptor field) {
        ModelMeta relation = ModelMeta.of(field.relatedClass());
        String relationAlias;

        if (field.useRelatedForeignKey()) {
            // 语义是「按子表指向本表的外键统计子表」：要统计的列在子表上，
            // 因此别名必须指向 JOIN 进来的子表。注意不能直接用 aliasOf：
            // 子表和主表同名（例如两个模型映射同一张表）时它会返回主表别名。
            relationAlias = joinAliasOf(relation.table());
            if (relationAlias == null) {
                String newAlias = requestAlias(relation.table());
                Optional<FieldDescriptor> fk = relation.fields().stream()
                        .filter(f -> f.type() == FieldType.ASSOCIATION_ID)
                        .filter(f -> f.relatedClass() == meta.modelClass()
                                     || ModelMeta.of(f.relatedClass()).table().equals(meta.table()))
                        .findFirst();
                if (fk.isEmpty()) {
                    throw new ModelDefinitionException(
                            "聚合字段 " + meta.modelClass().getSimpleName() + "." + field.name()
                            + " 需要 " + relation.modelClass().getSimpleName() + " 上指向本模型（表 "
                            + meta.table() + "）的外键字段，但未找到");
                }
                joinedRelations.add(relation.table());
                joins.add(new JoinCondition(relation.table(), newAlias,
                        aliased(baseAlias, meta.idColumn()) + " = " + aliased(newAlias, fk.get().column())));
                relationAlias = newAlias;
            }
        } else {
            relationAlias = aliasOf(relation.table());
            if (relationAlias == null) {
                throw new ModelDefinitionException(
                        "聚合字段 " + meta.modelClass().getSimpleName() + "." + field.name()
                        + " 需要先关联 " + relation.modelClass().getSimpleName()
                        + "，但它没有出现在任何关联字段上。"
                        + "两种改法：在本模型上补一个类型为 ASSOCIATION_MODEL、byModel = "
                        + relation.modelClass().getSimpleName() + " 的字段；"
                        + "或者置 useRelatedForeignKey = true，改由 "
                        + relation.modelClass().getSimpleName() + " 指向本表的外键来统计");
            }
        }

        String target = aliased(relationAlias, field.column());
        if (field.function() == SqlFunction.COUNT) {
            return "COUNT(DISTINCT " + target + ")";
        }
        return field.function().getSqlName() + "(" + target + ")";
    }

    private String aliasOf(String table) {
        if (baseAlias.equals(Aliases.of(table))) {
            return baseAlias;
        }
        return joinAliasOf(table);
    }

    /**
     * 只查 JOIN 进来的别名，不把主表算进去。
     *
     * @param table 表名
     * @return 别名，未 JOIN 时为 {@code null}
     */
    private String joinAliasOf(String table) {
        for (JoinCondition join : joins) {
            if (join.getJoinTable().equals(table)) {
                return join.getAlias();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 条件与排序

    /**
     * 按 Java 字段名或列名解析出带别名限定的列表达式。
     *
     * @param nameOrColumn 字段名或列名
     * @return 列表达式
     * @throws IllegalArgumentException 字段不存在
     */
    public String resolveColumn(String nameOrColumn) {
        FieldDescriptor field = meta.resolve(nameOrColumn).orElseThrow(
                () -> new IllegalArgumentException(
                        "模型 " + meta.modelClass().getSimpleName() + " 上不存在字段或列「" + nameOrColumn + "」"));
        if (field.type() == FieldType.AGGREGATION) {
            // 聚合列引用的是 SELECT 里的别名，必须和别名同样处理（按方言加引号）
            return dialect.quote(field.name());
        }
        return aliased(baseAlias, field.column());
    }

    /**
     * 追加一个等值过滤条件。
     *
     * @param nameOrColumn 字段名或列名
     * @param value        值，为空时忽略
     * @return 当前查询
     */
    public Query where(String nameOrColumn, Object value) {
        return where(nameOrColumn, OperatorType.EQ, value);
    }

    /**
     * 追加一个过滤条件。
     *
     * @param nameOrColumn 字段名或列名
     * @param operator     操作符
     * @param value        值；{@link OperatorType#IS_NULL} 系列忽略
     * @return 当前查询
     */
    public Query where(String nameOrColumn, OperatorType operator, Object value) {
        FieldDescriptor field = meta.resolve(nameOrColumn).orElseThrow(
                () -> new IllegalArgumentException(
                        "模型 " + meta.modelClass().getSimpleName() + " 上不存在字段或列「" + nameOrColumn + "」"));
        if (operator.needsValue() && ValueConverter.isBlank(value)) {
            return this;
        }
        if (field.type() == FieldType.AGGREGATION) {
            // HAVING 里不能引用 SELECT 的别名（PostgreSQL 只认 GROUP BY 的列与聚合表达式），
            // 所以这里放聚合表达式本身，与 selectAggregates 里用的是同一个字符串。
            havingConditions.add(new SqlCondition(aggregateExpressionOf(field), operator, value));
            return this;
        }
        whereConditions.add(new SqlCondition(aliased(baseAlias, field.column()), operator, value));
        return this;
    }

    /**
     * 追加一个「包含」过滤条件（{@code LIKE '%值%'}）。
     * <p>
     * 值仍然走占位符，通配符由 SQL 的 {@code CONCAT} 拼接，不把用户输入拼进 SQL 文本。
     *
     * @param nameOrColumn 字段名或列名
     * @param value        值，为空时忽略
     * @return 当前查询
     */
    public Query like(String nameOrColumn, Object value) {
        return where(nameOrColumn, OperatorType.LIKE, value);
    }

    /**
     * 追加一个「属于」过滤条件（{@code IN}）。
     *
     * @param nameOrColumn 字段名或列名
     * @param values       取值集合，为空时忽略
     * @return 当前查询
     */
    public Query in(String nameOrColumn, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        return where(nameOrColumn, OperatorType.IN, values);
    }

    /**
     * 追加一个「区间」过滤条件，两端都闭。
     * <p>
     * 只给一端时只加那一端的条件，两端都给 {@code null} 时什么也不加。
     * 参数顺序固定为「先小后大」，与 {@code ?} 占位符一一对应。
     *
     * @param nameOrColumn 字段名或列名
     * @param from         下界，可为 {@code null}
     * @param to           上界，可为 {@code null}
     * @return 当前查询
     */
    public Query between(String nameOrColumn, Object from, Object to) {
        where(nameOrColumn, OperatorType.GTE, from);
        where(nameOrColumn, OperatorType.LTE, to);
        return this;
    }

    /**
     * 追加排序。
     *
     * @param nameOrColumn 字段名或列名
     * @param sort         排序方向
     * @return 当前查询
     */
    public Query orderBy(String nameOrColumn, SortType sort) {
        if (nameOrColumn == null || nameOrColumn.isBlank()) {
            return this;
        }
        Optional<FieldDescriptor> field = meta.resolve(nameOrColumn);
        if (field.isEmpty()) {
            return this;
        }
        FieldDescriptor descriptor = field.get();
        String expression = descriptor.type() == FieldType.AGGREGATION
                ? dialect.quote(descriptor.name())
                : aliased(baseAlias, descriptor.column());
        orderBy.put(expression, sort == null ? SortType.ASC : sort);
        return this;
    }

    /**
     * 设置分页。
     *
     * @param pageNumber 页码，从 1 开始；{@code null} 或非正数表示不分页
     * @param pageSize   每页条数
     * @return 当前查询
     */
    public Query page(Integer pageNumber, int pageSize) {
        if (pageNumber == null || pageNumber <= 0 || pageSize <= 0) {
            this.limit = null;
            this.offset = null;
        } else {
            this.limit = pageSize;
            this.offset = (pageNumber - 1) * pageSize;
        }
        return this;
    }

    /**
     * 直接设置分页参数，用于「临时取一条再还原」这类场景。
     * <p>
     * 与 {@link #page(Integer, int)} 的区别：这里不折算页码，原样写入 limit 与 offset，
     * 因此可以精确还原调用前设置的值。两者都为 {@code null} 表示不分页。
     *
     * @param limit  行数
     * @param offset 偏移量
     * @return 当前查询
     */
    public Query limit(Integer limit, Integer offset) {
        if (limit == null || limit <= 0) {
            this.limit = null;
            this.offset = null;
        } else {
            this.limit = limit;
            this.offset = offset == null || offset < 0 ? null : offset;
        }
        return this;
    }

    /**
     * 添加分组字段。
     *
     * @param nameOrColumn 字段名或列名
     * @return 当前查询
     */
    public Query groupBy(String nameOrColumn) {
        groupBy.add(resolveColumn(nameOrColumn));
        return this;
    }

    // ---------------------------------------------------------------- 元数据出入

    /**
     * 模型元数据。
     *
     * @return 元数据
     */
    public ModelMeta meta() {
        return meta;
    }

    /**
     * 主表别名。
     *
     * @return 别名
     */
    public String baseAlias() {
        return baseAlias;
    }

    /**
     * 主表名。
     *
     * @return 表名
     */
    public String table() {
        return meta.table();
    }

    /**
     * 选择列表。
     *
     * @return 选择项
     */
    public List<SelectedField> selectedFields() {
        return List.copyOf(selectedFields);
    }

    /**
     * 关联条件。
     *
     * @return 关联列表
     */
    public List<JoinCondition> joins() {
        return List.copyOf(joins);
    }

    /**
     * WHERE 条件。
     *
     * @return 条件列表
     */
    public List<SqlCondition> whereConditions() {
        return List.copyOf(whereConditions);
    }

    /**
     * HAVING 条件。
     *
     * @return 条件列表
     */
    public List<SqlCondition> havingConditions() {
        return List.copyOf(havingConditions);
    }

    /**
     * 排序。
     *
     * @return 排序映射
     */
    public Map<String, SortType> orderByMap() {
        return new LinkedHashMap<>(orderBy);
    }

    /**
     * 分组。
     *
     * @return 分组集合
     */
    public Collection<String> groupByColumns() {
        return new ArrayList<>(groupBy);
    }

    /**
     * 每页条数。
     *
     * @return 条数，未分页为 {@code null}
     */
    public Integer limit() {
        return limit;
    }

    /**
     * 偏移量。
     *
     * @return 偏移量，未分页为 {@code null}
     */
    public Integer offset() {
        return offset;
    }

    /**
     * 是否包含聚合列。
     *
     * @return 包含返回 {@code true}
     */
    public boolean hasAggregates() {
        return !meta.aggregateFields().isEmpty();
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 拼「别名.列」。列名按方言加引号：PostgreSQL 里未加引号的标识符会被折叠成小写，
     * 模型里写了 {@code studentId} 这种驼峰列名时就会找不到列。
     */
    private String aliased(String alias, String column) {
        return alias + "." + dialect.quote(column);
    }

    /**
     * 选择别名。这里保持原样不加引号：结果集的列标签用的就是这个名字，
     * 由 {@link SqlStatement} 在渲染 SQL 时按方言加引号。
     */
    private String selectAlias(String alias) {
        return alias;
    }

    /**
     * 把名字转换成合法的 SQL 别名。
     */
    private static final class Aliases {
        /**
         * 别名统一前缀。表名本身可能就是 SQL 保留字（{@code user}、{@code order}、{@code group}
         * 之类），若直接拿表名当别名，PostgreSQL 会把 {@code user."user_id"} 里的 {@code user}
         * 当成 {@code CURRENT_USER} 解析，导致「语法错误 在 "." 或附近」。
         * 加前缀后别名不可能与保留字相同，也不需要为每个方言维护关键字表。
         */
        private static final String PREFIX = "t_";

        private Aliases() {
        }

        static String of(String table) {
            String sanitized = table == null ? "" : table.replaceAll("[^A-Za-z0-9_]", "");
            if (sanitized.isBlank()) {
                throw new ModelDefinitionException("无法从表名「" + table + "」推导出 SQL 别名");
            }
            return PREFIX + Character.toLowerCase(sanitized.charAt(0)) + sanitized.substring(1);
        }
    }
}
