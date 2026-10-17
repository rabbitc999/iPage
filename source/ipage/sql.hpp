/**
 * @file sql.hpp
 * @brief SQL 构建：`Condition` / `Order` / `SelectedField` / `Query` / `SqlStatement`。
 *
 * 对应 Java 版的 `sql/SqlCondition`、`sql/JoinCondition`、`sql/SelectedField`、
 * `sql/Query`、`sql/SqlStatement`、`sql/Order`。
 *
 * ## 设计要点
 *
 *   - **条件树**：`Condition` 是 `Field / Value / And / Or / Not` 的递归结构，
 *     `toSql()` 遍历时把值推进 `std::vector<Cell> &`，SQL 与参数一次成型，
 *     不存在「先拼字符串再补参数」的顺序错位。
 *   - **别名**：主表别名固定 `t_`，关联表别名 `t_2`、`t_3`……（与 Java 版一致）。
 *   - **列白名单**：`Field` 条件里的列名只能来自 `ModelMeta`，外部输入无法进入 SQL。
 *   - **`&` / `|` 运算符**：`ipage::field("age") > 18 && ipage::field("age") < 30`，
 *     比 `and_(gt(...), lt(...))` 好读得多。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#ifndef IPAGE_SQL_HPP
#define IPAGE_SQL_HPP

#include "dialect.hpp"
#include "field.hpp"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace ipage
{

class Condition;
class Query;
class SqlStatement;
class ModelMeta;

// ---------------------------------------------------------------------------
//  条件
// ---------------------------------------------------------------------------

/**
 * @brief 一个可拼成 SQL 的条件节点。
 *
 * 使用者多数时候不直接构造它，而是从 `field(...)` 出发写比较表达式。
 */
class Condition
{
public:
    /** 节点种类。 */
    enum class Kind
    {
        Field, ///< 列与值比较。
        Value, ///< 恒真 / 恒假 / 子查询占位。
        And,   ///< 合取。
        Or,    ///< 析取。
        Not    ///< 取反。
    };

    Condition() = delete;

    /** 叶子：列与值比较。 */
    struct FieldTerm
    {
        std::string column;                    ///< 列名（表内名，会按别名限定）。
        OperatorType op = OperatorType::Eq;    ///< 操作符。
        std::vector<Cell> values;              ///< 绑定值（`In` / `NotIn` 可为多个）。
        bool rawColumn = false;                ///< `column` 是否已是完整表达式（不再限定别名）。
    };

    /**
     * @brief 列比较条件。
     * @param column 列名。
     * @param op 操作符。
     * @param values 绑定值。
     * @param rawColumn 列名是否已含表前缀。
     * @return 条件。
     */
    static Condition compare(std::string column, OperatorType op, std::vector<Cell> values,
                             bool rawColumn = false);

    /**
     * @brief 恒真。
     * @return 条件。
     */
    static Condition alwaysTrue();

    /**
     * @brief 恒假。
     * @return 条件。
     */
    static Condition alwaysFalse();

    /**
     * @brief 合取。空列表视为恒真。
     * @param parts 子条件。
     * @return 条件。
     */
    static Condition conjunction(std::vector<Condition> parts);

    /**
     * @brief 析取。空列表视为恒假。
     * @param parts 子条件。
     * @return 条件。
     */
    static Condition disjunction(std::vector<Condition> parts);

    /**
     * @brief 取反。
     * @param part 子条件。
     * @return 条件。
     */
    static Condition negation(Condition part);

    /** 节点种类。 */
    [[nodiscard]] Kind kind() const noexcept { return kind_; }

    /**
     * @brief 叶子字段条件。
     * @return 叶子节点；不是叶子时为空。
     */
    [[nodiscard]] const FieldTerm *leaf() const noexcept
    {
        return kind_ == Kind::Field ? &term_ : nullptr;
    }

    /**
     * @brief 子节点。
     * @return 子节点列表。
     */
    [[nodiscard]] const std::vector<Condition> &children() const noexcept { return children_; }

    /**
     * @brief 渲染成 SQL 片段，同时把值追加到 `params`。
     * @param out 输出缓冲。
     * @param params 参数收集器。
     * @param dialect 方言。
     * @param tableAlias 当前主表别名（用于限定列）。
     */
    void render(std::string &out, std::vector<Cell> &params, const Dialect &dialect,
                std::string_view tableAlias) const;

    /**
     * @brief 是否需要 `WHERE`（恒真且无子条件时可省略）。
     * @return 需要返回 true。
     */
    [[nodiscard]] bool isTrivial() const noexcept
    {
        return kind_ == Kind::Value && !constant_;
    }

    /**
     * @brief 合取追加。
     * @param other 另一个条件。
     * @return 合取结果。
     */
    [[nodiscard]] Condition operator&&(Condition other) const;

    /**
     * @brief 析取追加。
     * @param other 另一个条件。
     * @return 析取结果。
     */
    [[nodiscard]] Condition operator||(Condition other) const;

private:
    explicit Condition(Kind kind) noexcept : kind_(kind) {}

    Kind kind_ = Kind::Field;
    bool constant_ = false; ///< 仅 `Kind::Value` 有意义：true 恒真，false 恒假。
    FieldTerm term_{};
    std::vector<Condition> children_;
};

// ---------------------------------------------------------------------------
//  排序 / 选列
// ---------------------------------------------------------------------------

/**
 * @brief 一个排序项。
 */
struct Order
{
    std::string column;                       ///< 列名（表内名）。
    SortType direction = SortType::Asc;       ///< 方向。
    std::string tableAlias = "t_";            ///< 该列所属的别名。
};

/**
 * @brief 一个被选中的字段。
 *
 * 三种形态：
 *   - 普通列：`column` 非空、`function` 为空。
 *   - 聚合：`function` 有值，`column` 可为空（`COUNT(*)`）。
 *   - 关联字段：`relatedAlias` 非空，`column` 取自关联表，`relatedColumn` 为关联表里的真列。
 */
struct SelectedField
{
    std::string aliasName;    ///< 模型里的字段名。
    std::string column;       ///< 主表列名；聚合时为被聚合的列。
    std::string tableAlias;   ///< 主表别名或关联表别名。
    std::optional<SqlFunction> function; ///< 聚合函数。
    bool isAggregate = false; ///< 是否聚合（决定 GROUP BY 是否包含它）。
    bool isJoin = false;      ///< 是否来自 LEFT JOIN 的关联表。    const ModelMeta *related = nullptr;  ///< 关联字段指向的模型（非空则要 LEFT JOIN）。
    std::string relatedAlias; ///< 关联表别名。
    std::string relatedForeignKey; ///< 关联表上指回主表的列。
    std::string foreignKey;   ///< 主表上指向关联表的列。
};

// ---------------------------------------------------------------------------
//  Query
// ---------------------------------------------------------------------------

/**
 * @brief 一条 SELECT 的完整描述（不含分页）。
 *
 * 由 `Query<Model>::of(...)` 起步，链式配置后交给 `Session` 执行。
 */
class Query
{
public:
    /** 主表别名（与 Java 版一致）。 */
    static constexpr std::string_view kMainAlias = "t_";

    /**
     * @brief 构造。
     * @param meta 主模型元数据。
     * @param dialect 方言。
     */
    Query(const ModelMeta &meta, const Dialect &dialect);

    /** 拷贝（条件树深拷贝，其余浅拷）。 */
    Query(const Query &other);
    /** 拷贝赋值。 */
    Query &operator=(const Query &other);
    /** 移动。 */
    Query(Query &&other) noexcept = default;
    /** 移动赋值。 */
    Query &operator=(Query &&other) noexcept = default;
    ~Query() = default;

    /** 主模型的元数据。 */
    [[nodiscard]] const ModelMeta &meta() const noexcept { return *meta_; }
    /** 方言。 */
    [[nodiscard]] const Dialect &dialect() const noexcept { return *dialect_; }

    /**
     * @brief 追加一个列条件（不会替换已有条件，而是合取）。
     * @param column 列名。
     * @param op 操作符。
     * @param values 绑定值。
     * @return 自身引用。
     */
    Query &filter(std::string column, OperatorType op, std::vector<Cell> values);

    /**
     * @brief 追加一个任意条件。
     * @param condition 条件。
     * @return 自身引用。
     */
    Query &where(Condition condition);

    /**
     * @brief 追加排序。
     * @param column 列名。
     * @param direction 方向。
     * @return 自身引用。
     */
    Query &orderBy(std::string column, SortType direction = SortType::Asc);

    /**
     * @brief 限定条数。
     * @param value 条数。
     * @return 自身引用。
     */
    Query &limit(long long value);
    /**
     * @brief 跳过条数。
     * @param value 条数。
     * @return 自身引用。
     */
    Query &offset(long long value);

    /**
     * @brief 指定选中的列（默认全选）。
     * @param columns 列名列表。
     * @return 自身引用。
     */
    Query &select(std::vector<std::string> columns);

    /**
     * @brief 追加聚合列。
     * @param aliasName 结果里的字段名（会用于映射）。
     * @param column 被聚合的列；空串表示 `*`。
     * @param function 聚合函数。
     * @return 自身引用。
     */
    Query &aggregate(std::string aliasName, std::string column, SqlFunction function);

    /**
     * @brief 只为 `COUNT(*)` 服务：清空选列，只留计数。
     * @return 自身引用。
     */
    Query &countOnly();

    /**
     * @brief 追加一个 LEFT JOIN 的关联模型（别名自动分配为 `t_2`、`t_3`……）。
     * @param related 关联模型。
     * @param foreignKey 主表上指向关联表的列。
     * @param relatedForeignKey 关联表上被指向的列。
     * @return 自身引用。
     * @throws std::invalid_argument 列名不存在。
     */
    Query &join(const ModelMeta &related, std::string foreignKey, std::string relatedForeignKey);

    /**
     * @brief 按关联字段名装配：自动 LEFT JOIN 并选中关联表的全部列。
     *
     * 列别名形如 `sclass.id`，`Session` 装配时按同一前缀回填进嵌套成员。
     * 字段必须以 `ipage::association(...)` 声明（类型 `AssociationModel`、
     * 带 `related` 元数据），否则抛 `std::invalid_argument`。
     *
     * @param fieldName 关联字段名。
     * @return 自身引用。
     * @throws std::invalid_argument 字段不存在或不是关联字段。
     */
    Query &with(const std::string &fieldName);

    /** 当前条件。 */
    [[nodiscard]] const Condition *condition() const noexcept { return condition_.get(); }
    /** 当前排序。 */
    [[nodiscard]] const std::vector<Order> &orders() const noexcept { return orders_; }
    /** 当前选列。 */
    [[nodiscard]] const std::vector<SelectedField> &selections() const noexcept { return selections_; }
    /** 是否只做计数。 */
    [[nodiscard]] bool counting() const noexcept { return counting_; }
    /** 条数限制（0 表示不限）。 */
    [[nodiscard]] long long limitValue() const noexcept { return limit_; }
    /** 跳过条数。 */
    [[nodiscard]] long long offsetValue() const noexcept { return offset_; }

    /**
     * @brief 渲染成可执行语句。
     * @return 语句（含 SQL 与参数）。
     */
    [[nodiscard]] SqlStatement build() const;

private:
    void ensureDefaultSelections();

    /** 一条 LEFT JOIN 的描述。 */
    struct JoinSpec
    {
        const ModelMeta *meta = nullptr; ///< 被关联的模型。
        std::string alias;               ///< 关联表别名。
        std::string foreignKey;          ///< 主表上指向关联表的列。
        std::string relatedForeignKey;   ///< 关联表上被指向的列。
    };

    const ModelMeta *meta_ = nullptr;
    const Dialect *dialect_ = nullptr;
    std::unique_ptr<Condition> condition_;
    std::vector<Order> orders_;
    std::vector<SelectedField> selections_;
    std::vector<std::string> requestedColumns_;
    std::vector<JoinSpec> joins_;
    bool explicitSelection_ = false; ///< select/aggregate 显式给过选列。
    bool counting_ = false;
    long long limit_ = 0;
    long long offset_ = 0;
};

// ---------------------------------------------------------------------------
//  SqlStatement
// ---------------------------------------------------------------------------

/**
 * @brief 一条待执行的 SQL：语句文本 + 按顺序绑定的参数。
 */
class SqlStatement
{
public:
    /** 构造。 */
    SqlStatement() = default;

    /**
     * @brief 构造。
     * @param sql 语句文本。
     * @param params 参数。
     */
    SqlStatement(std::string sql, std::vector<Cell> params)
        : sql_(std::move(sql)), params_(std::move(params))
    {
    }

    /** 语句文本。 */
    [[nodiscard]] const std::string &sql() const noexcept { return sql_; }
    /** 参数。 */
    [[nodiscard]] const std::vector<Cell> &params() const noexcept { return params_; }

    /** 追加参数。 */
    void addParam(Cell value) { params_.push_back(std::move(value)); }

    /** 诊断：`SQL -- [params]`。 */
    [[nodiscard]] std::string toString() const;

    /** 两个语句是否完全相同（测试用）。 */
    friend bool operator==(const SqlStatement &left, const SqlStatement &right)
    {
        return left.sql_ == right.sql_ && left.params_ == right.params_;
    }

private:
    std::string sql_;
    std::vector<Cell> params_;
};

// ---------------------------------------------------------------------------
//  条件构造辅助
// ---------------------------------------------------------------------------

/**
 * @brief 从一个列名起步写条件。
 * @param column 列名。
 * @return 条件（默认 `= NULL`，需要配 `==` 才会成型）。
 *
 * @note 直接 `field("age") > 18` 用；单独一个 `field("age")` 无意义。
 */
class FieldRef
{
public:
    /** 构造。 */
    explicit FieldRef(std::string column) : column_(std::move(column)) {}

    /** 列名。 */
    [[nodiscard]] const std::string &column() const noexcept { return column_; }

    /** `= 值`。 */
    [[nodiscard]] Condition operator==(Cell value) const;
    /** `<> 值`。 */
    [[nodiscard]] Condition operator!=(Cell value) const;
    /** `> 值`。 */
    [[nodiscard]] Condition operator>(Cell value) const;
    /** `< 值`。 */
    [[nodiscard]] Condition operator<(Cell value) const;
    /** `>= 值`。 */
    [[nodiscard]] Condition operator>=(Cell value) const;
    /** `<= 值`。 */
    [[nodiscard]] Condition operator<=(Cell value) const;

    /** `IS NULL`。 */
    [[nodiscard]] Condition isNull() const;
    /** `IS NOT NULL`。 */
    [[nodiscard]] Condition isNotNull() const;

    /**
     * @brief `LIKE '%值%'`。
     * @param value 子串。
     * @return 条件。
     */
    [[nodiscard]] Condition contains(std::string_view value) const;
    /**
     * @brief `LIKE '值%'`。
     * @param value 前缀。
     * @return 条件。
     */
    [[nodiscard]] Condition startsWith(std::string_view value) const;
    /**
     * @brief `LIKE '%值'`。
     * @param value 后缀。
     * @return 条件。
     */
    [[nodiscard]] Condition endsWith(std::string_view value) const;

    /**
     * @brief `IN (…)`。
     * @param values 值列表。
     * @return 条件。
     */
    [[nodiscard]] Condition in(std::vector<Cell> values) const;
    /**
     * @brief `NOT IN (…)`。
     * @param values 值列表。
     * @return 条件。
     */
    [[nodiscard]] Condition notIn(std::vector<Cell> values) const;

private:
    std::string column_;
};

/**
 * @brief 从一个列名起步写条件。
 * @param column 列名。
 * @return 列引用。
 */
[[nodiscard]] FieldRef field(std::string column);

/**
 * @brief 恒真条件。
 * @return 条件。
 */
[[nodiscard]] Condition alwaysTrue();
/**
 * @brief 恒假条件。
 * @return 条件。
 */
[[nodiscard]] Condition alwaysFalse();

/**
 * @brief 合取（可读写法，等价于 `&&`）。
 * @param parts 子条件。
 * @return 条件。
 */
[[nodiscard]] Condition all(std::vector<Condition> parts);
/**
 * @brief 析取（可读写法，等价于 `||`）。
 * @param parts 子条件。
 * @return 条件。
 */
[[nodiscard]] Condition any(std::vector<Condition> parts);
/**
 * @brief 取反。
 * @param part 子条件。
 * @return 条件。
 */
[[nodiscard]] Condition none(Condition part);

} // namespace ipage

#endif // IPAGE_SQL_HPP
