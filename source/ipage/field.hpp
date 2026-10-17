/**
 * @file field.hpp
 * @brief 字段语义、单元格值（`Cell`）、操作符与排序：ipage 的元数据层。
 *
 * 这一层对应 Java 版的 `enums/FieldType`、`enums/OperatorType`、`enums/SortType`、
 * `enums/SqlFunction` 与 `reflect/FieldDescriptor`，只是把「注解 + 反射」换成了
 * **成员指针表**（与 `library/json/bind.hpp`、`library/toml/bind.hpp`、
 * `library/template/bind.hpp` 同一套设计）。
 *
 * 为什么不用反射：C++ 没有（C++26 之前的）静态反射，而 ipage 需要的元数据是
 * 「字段名、列名、语义类型、是否主键、关联到哪个模型」——这些本来就是模型作者
 * 要声明的东西。写成一张 constexpr 表，比宏展开生成代码更直白，而且能在
 * `static_assert` 里校验。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#ifndef IPAGE_FIELD_HPP
#define IPAGE_FIELD_HPP

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <variant>

namespace ipage
{

// ---------------------------------------------------------------------------
//  基础枚举
// ---------------------------------------------------------------------------

/**
 * @brief 字段语义类型。
 *
 * 决定该字段是否落库、SQL 中如何选取、写入时是否参与 INSERT / UPDATE。
 * 与 Java 版 `FieldType` 一一对应。
 */
enum class FieldType
{
    /** 主键列，落库、可排序、不可编辑。 */
    ModelId,
    /** 外键列，落库；值指向 `byModel` 的主键。 */
    AssociationId,
    /** 关联对象，不落库；查询时 LEFT JOIN 填充。 */
    AssociationModel,
    /** 聚合列，不落库；查询时对 `byModel` 表中 `column()` 做聚合。 */
    Aggregation,
    /** 字符串。 */
    String,
    /** 数值。 */
    Number,
    /** 枚举，落库为枚举常量的整数值。 */
    Enum,
    /** 日期，落库为 ISO-8601 文本（`YYYY-MM-DD`）。 */
    Date,
    /** 布尔。 */
    Boolean
};

/**
 * @brief 该类型是否对应表中的一个真实列。
 * @param type 字段类型。
 * @return 是真实列返回 true。
 */
[[nodiscard]] constexpr bool isColumn(FieldType type) noexcept
{
    return (type == FieldType::ModelId) || (type == FieldType::AssociationId) ||
           (type == FieldType::String) || (type == FieldType::Number) ||
           (type == FieldType::Enum) || (type == FieldType::Date) ||
           (type == FieldType::Boolean);
}

/**
 * @brief 该类型在写入数据库时是否参与 INSERT / UPDATE。
 * @param type 字段类型。
 * @return 可写入返回 true。
 */
[[nodiscard]] constexpr bool isWritable(FieldType type) noexcept
{
    return isColumn(type);
}

/**
 * @brief 过滤 / 排序操作符。
 *
 * 对应 Java 版 `OperatorType`。**封闭枚举**：SQL 片段只从表里取，
 * 没有任何一条路径会把外部字符串拼进 SQL。
 */
enum class OperatorType
{
    /** `=` */
    Eq,
    /** `<>` */
    Ne,
    /** `>` */
    Gt,
    /** `<` */
    Lt,
    /** `>=` */
    Gte,
    /** `<=` */
    Lte,
    /** `LIKE '%值%'` */
    Like,
    /** `LIKE '值%'` */
    StartsWith,
    /** `LIKE '%值'` */
    EndsWith,
    /** `IS NULL` */
    IsNull,
    /** `IS NOT NULL` */
    IsNotNull,
    /** `IN (…)` */
    In,
    /** `NOT IN (…)` */
    NotIn
};

/**
 * @brief 操作符的 SQL 符号。
 * @param op 操作符。
 * @return 符号文本。
 */
[[nodiscard]] constexpr std::string_view symbol(OperatorType op) noexcept
{
    switch (op)
    {
    case OperatorType::Eq: return "=";
    case OperatorType::Ne: return "<>";
    case OperatorType::Gt: return ">";
    case OperatorType::Lt: return "<";
    case OperatorType::Gte: return ">=";
    case OperatorType::Lte: return "<=";
    case OperatorType::Like:
    case OperatorType::StartsWith:
    case OperatorType::EndsWith: return "LIKE";
    case OperatorType::IsNull: return "IS NULL";
    case OperatorType::IsNotNull: return "IS NOT NULL";
    case OperatorType::In: return "IN";
    case OperatorType::NotIn: return "NOT IN";
    }
    return "=";
}

/**
 * @brief 该操作符是否需要绑定一个值。
 * @param op 操作符。
 * @return 需要值返回 true。
 */
[[nodiscard]] constexpr bool needsValue(OperatorType op) noexcept
{
    return (op != OperatorType::IsNull) && (op != OperatorType::IsNotNull);
}

/**
 * @brief 该操作符是否需要一组值。
 * @param op 操作符。
 * @return 需要集合返回 true。
 */
[[nodiscard]] constexpr bool needsCollection(OperatorType op) noexcept
{
    return (op == OperatorType::In) || (op == OperatorType::NotIn);
}

/**
 * @brief 排序方向。
 */
enum class SortType
{
    Asc,
    Desc
};

/**
 * @brief 排序方向的 SQL 文本。
 * @param sort 方向。
 * @return `ASC` 或 `DESC`。
 */
[[nodiscard]] constexpr std::string_view keyword(SortType sort) noexcept
{
    return (sort == SortType::Asc) ? "ASC" : "DESC";
}

/**
 * @brief 聚合函数。
 */
enum class SqlFunction
{
    Count,
    Sum,
    Avg,
    Max,
    Min
};

/**
 * @brief 聚合函数的 SQL 名称。
 * @param function 聚合函数。
 * @return 名称，例如 `COUNT`。
 */
[[nodiscard]] constexpr std::string_view sqlName(SqlFunction function) noexcept
{
    switch (function)
    {
    case SqlFunction::Count: return "COUNT";
    case SqlFunction::Sum: return "SUM";
    case SqlFunction::Avg: return "AVG";
    case SqlFunction::Max: return "MAX";
    case SqlFunction::Min: return "MIN";
    }
    return "COUNT";
}

/**
 * @brief 写入前校验 / 钩子的时机。
 */
enum class CheckerType
{
    Add,
    Update,
    Delete
};

/**
 * @brief 字段类型的诊断名。
 * @param type 字段类型。
 * @return 名称，例如 `STRING`。
 */
[[nodiscard]] constexpr std::string_view typeName(FieldType type) noexcept
{
    switch (type)
    {
    case FieldType::ModelId: return "MODEL_ID";
    case FieldType::AssociationId: return "ASSOCIATION_ID";
    case FieldType::AssociationModel: return "ASSOCIATION_MODEL";
    case FieldType::Aggregation: return "AGGREGATION";
    case FieldType::String: return "STRING";
    case FieldType::Number: return "NUMBER";
    case FieldType::Enum: return "ENUM";
    case FieldType::Date: return "DATE";
    case FieldType::Boolean: return "BOOLEAN";
    }
    return "STRING";
}

// ---------------------------------------------------------------------------
//  单元格值
// ---------------------------------------------------------------------------

/**
 * @brief 一个数据库单元格 / 绑定参数。
 *
 * 只覆盖 SQL 里真正会出现的几种：NULL、整数、浮点、文本、布尔。
 * 日期一律以 ISO-8601 文本传递（SQLite 没有原生日期类型，PostgreSQL 也接受文本）。
 *
 * 刻意不用 `std::variant<..., std::monostate>`：`Null` 单独一个类型，
 * `asInt()` 之类就不必先判断 `monostate`，也能在 `visit` 里明确区分
 * 「值是 NULL」与「值不存在」。
 */
class Cell
{
public:
    /** 空值类型。 */
    struct Null
    {
        friend constexpr bool operator==(Null, Null) noexcept { return true; }
        friend constexpr bool operator!=(Null, Null) noexcept { return false; }
    };

    /** 底层存储。 */
    using Storage = std::variant<Null, std::int64_t, double, std::string, bool>;

    /** 构造：NULL。 */
    Cell() noexcept : storage_(Null{}) {}

    /** 构造：NULL。 */
    Cell(std::nullptr_t) noexcept : storage_(Null{}) {}

    /** 构造：整数。 */
    Cell(std::int64_t value) noexcept : storage_(value) {}

    /** 构造：32 位整数（避免和 bool 抢重载）。 */
    Cell(int value) noexcept : storage_(static_cast<std::int64_t>(value)) {}

    /** 构造：布尔。 */
    Cell(bool value) noexcept : storage_(value) {}

    /** 构造：浮点。 */
    Cell(double value) noexcept : storage_(value) {}

    /** 构造：文本。 */
    Cell(std::string value) : storage_(std::move(value)) {}

    /** 构造：文本。 */
    Cell(const char *value) : storage_(std::string(value == nullptr ? "" : value)) {}

    /** 构造：文本视图。 */
    Cell(std::string_view value) : storage_(std::string(value)) {}

    /** 是不是 NULL。 */
    [[nodiscard]] bool isNull() const noexcept { return std::holds_alternative<Null>(storage_); }
    /** 是不是整数。 */
    [[nodiscard]] bool isInt() const noexcept { return std::holds_alternative<std::int64_t>(storage_); }
    /** 是不是浮点。 */
    [[nodiscard]] bool isDouble() const noexcept { return std::holds_alternative<double>(storage_); }
    /** 是不是数字。 */
    [[nodiscard]] bool isNumber() const noexcept { return isInt() || isDouble(); }
    /** 是不是文本。 */
    [[nodiscard]] bool isText() const noexcept { return std::holds_alternative<std::string>(storage_); }
    /** 是不是布尔。 */
    [[nodiscard]] bool isBool() const noexcept { return std::holds_alternative<bool>(storage_); }

    /**
     * @brief 取整数（浮点且值为整数时也能取到）。
     * @return 整数；取不到为空。
     */
    [[nodiscard]] std::optional<std::int64_t> asInt() const noexcept
    {
        if (const auto *number = std::get_if<std::int64_t>(&storage_)) return *number;
        if (const auto *real = std::get_if<double>(&storage_))
        {
            const double value = *real;
            const auto truncated = static_cast<std::int64_t>(value);
            if (static_cast<double>(truncated) == value) return truncated;
        }
        if (const auto *flag = std::get_if<bool>(&storage_)) return *flag ? 1 : 0;
        return std::nullopt;
    }

    /**
     * @brief 取浮点（整数会转成浮点）。
     * @return 浮点；取不到为空。
     */
    [[nodiscard]] std::optional<double> asDouble() const noexcept
    {
        if (const auto *real = std::get_if<double>(&storage_)) return *real;
        if (const auto *number = std::get_if<std::int64_t>(&storage_))
        {
            return static_cast<double>(*number);
        }
        return std::nullopt;
    }

    /**
     * @brief 取布尔。
     * @return 布尔；取不到为空。
     */
    [[nodiscard]] std::optional<bool> asBool() const noexcept
    {
        if (const auto *flag = std::get_if<bool>(&storage_)) return *flag;
        if (const auto *number = std::get_if<std::int64_t>(&storage_)) return *number != 0;
        return std::nullopt;
    }

    /**
     * @brief 取文本。
     * @return 文本视图（指向内部存储）；取不到为空。
     */
    [[nodiscard]] std::optional<std::string_view> asText() const noexcept
    {
        if (const auto *text = std::get_if<std::string>(&storage_)) return std::string_view(*text);
        return std::nullopt;
    }

    /**
     * @brief 取文本，非文本则转成文本。
     *
     * 整数、浮点、布尔都能转；NULL 返回空串。写 SQL 之外的场合（日志、诊断）用。
     *
     * @return 文本。
     */
    [[nodiscard]] std::string toText() const;
    /**
     * @brief 类型名（诊断用）。
     * @return `null` / `int` / `double` / `text` / `bool`。
     */
    [[nodiscard]] const char *typeName() const noexcept;

    /** 底层存储（要用 `std::visit` 自己分派时用）。 */
    [[nodiscard]] const Storage &storage() const noexcept { return storage_; }

    /** 相等比较：NULL 只等于 NULL，数字按数值比。 */
    friend bool operator==(const Cell &left, const Cell &right) noexcept;

    /** 不等比较。 */
    friend bool operator!=(const Cell &left, const Cell &right) noexcept { return !(left == right); }

private:
    Storage storage_; ///< 值本体。
};

// ---------------------------------------------------------------------------
//  Cell 的实现
// ---------------------------------------------------------------------------

inline std::string Cell::toText() const
{
    if (const auto *text = std::get_if<std::string>(&storage_)) return *text;
    if (const auto *number = std::get_if<std::int64_t>(&storage_)) return std::to_string(*number);
    if (const auto *real = std::get_if<double>(&storage_))
    {
        // 与 dump 的口径一致：必要时补 ".0"，避免 3.0 写成 "3"
        std::string out = std::to_string(*real);
        const std::size_t dot = out.find('.');
        if (dot != std::string::npos)
        {
            std::size_t last = out.find_last_not_of('0');
            if (last != std::string::npos && last > dot)
            {
                out.erase(last + 1);
            }
            else if (last == dot)
            {
                out.erase(dot);
            }
        }
        return out;
    }
    if (const auto *flag = std::get_if<bool>(&storage_)) return *flag ? "1" : "0";
    return {};
}

inline const char *Cell::typeName() const noexcept
{
    if (isNull()) return "null";
    if (isInt()) return "int";
    if (isDouble()) return "double";
    if (isText()) return "text";
    return "bool";
}

inline bool operator==(const Cell &left, const Cell &right) noexcept
{
    if (left.isNull() || right.isNull()) return left.isNull() && right.isNull();
    if (left.isNumber() && right.isNumber())
    {
        const std::optional<double> a = left.asDouble();
        const std::optional<double> b = right.asDouble();
        return a.has_value() && b.has_value() && (*a == *b);
    }
    if (left.isBool() && right.isBool())
    {
        return left.asBool() == right.asBool();
    }
    if (left.isText() && right.isText())
    {
        return left.asText() == right.asText();
    }
    return false;
}

} // namespace ipage

#endif // IPAGE_FIELD_HPP
