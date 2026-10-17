/**
 * @file model.hpp
 * @brief 模型元数据：`field` / `IPAGE_MODEL` / `metaOf<T>()`。
 *
 * 对应 Java 版的 `annotation` 包、`reflect/FieldDescriptor`、`reflect/ModelMeta`。
 * 绑定方式沿用 `library/json/bind.hpp`、`library/template/bind.hpp` 的**成员指针表**：
 *
 * ```cpp
 * struct Student
 * {
 *     int         id = 0;
 *     std::string name;
 *     int         sclassId = 0;
 * };
 *
 * IPAGE_MODEL(Student,
 *     ipage::id("id", &Student::id),
 *     ipage::text("name", &Student::name),
 *     ipage::foreignKey("sclassId", &Student::sclassId));
 *
 * const ipage::ModelMeta &meta = ipage::metaOf<Student>();
 * ```
 *
 * ## 两层结构
 *
 *   - **声明期**：`Field<T, M>` 持有成员指针，类型精确，`writeCell` 会做 `static_assert`。
 *   - **运行期**：`metaOf<T>()` 把字段表擦成 `std::vector<FieldInfo>`，每个字段带一对
 *     `std::function`（读 / 写，签名只跟 `Cell` 有关）。于是 `Query`、`SqlStatement`、
 *     `Session` 都不必是模板，代码量比「整条链路模板化」少一个数量级。
 *
 * 与 Java 版的差别（语言带来的，不是设计走样）：
 *
 *   - Java 用 `@ModelField(byModel = Sclass.class)` 指向一个类；C++ 在声明期拿不到
 *     另一个类的元数据，所以关联 / 聚合改为传 `Sclass::meta()`。
 *   - 列的 C++ 类型由成员指针推导，不必再写一次。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#ifndef IPAGE_MODEL_HPP
#define IPAGE_MODEL_HPP

#include "field.hpp"

#include <cstddef>
#include <functional>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>
#include <vector>

namespace ipage
{

class ModelMeta;

// ---------------------------------------------------------------------------
//  值转换：C++ 成员 ↔ Cell
// ---------------------------------------------------------------------------

namespace detail
{

/** 枚举转底层整数。 */
template <class E>
constexpr std::int64_t enumToInt(E value) noexcept
{
    return static_cast<std::int64_t>(static_cast<std::underlying_type_t<E>>(value));
}

/** 类名 → 表名：只在没有显式给表名时作为兜底。 */
inline std::string toSnakeCase(std::string_view name)
{
    std::string out;
    out.reserve(name.size() + 4);
    for (std::size_t index = 0; index < name.size(); ++index)
    {
        const char ch = name[index];
        const bool upper = (ch >= 'A') && (ch <= 'Z');
        if (upper)
        {
            const bool previousLower = index > 0 && name[index - 1] >= 'a' && name[index - 1] <= 'z';
            if (previousLower && !out.empty()) out.push_back('_');
            out.push_back(static_cast<char>(ch - 'A' + 'a'));
        }
        else
        {
            out.push_back(ch);
        }
    }
    return out;
}

} // namespace detail

/**
 * @brief 把一个成员值转成单元格。
 *
 * `std::optional<T>` 的空值映射为 NULL；`Cell` 原样透传。
 * 其它不能落库的类型（嵌套模型、容器）会在编译期被 `static_assert` 拦住。
 *
 * @param value 成员值。
 * @return 单元格。
 */
template <class M>
Cell toCell(const M &value)
{
    if constexpr (std::is_same_v<M, Cell>)
    {
        return value;
    }
    else if constexpr (std::is_same_v<M, bool>)
    {
        return Cell(static_cast<bool>(value));
    }
    else if constexpr (std::is_enum_v<M>)
    {
        return Cell(detail::enumToInt(value));
    }
    else if constexpr (std::is_integral_v<M>)
    {
        return Cell(static_cast<std::int64_t>(value));
    }
    else if constexpr (std::is_floating_point_v<M>)
    {
        return Cell(static_cast<double>(value));
    }
    else if constexpr (std::is_same_v<M, std::string> || std::is_same_v<M, std::string_view>)
    {
        return Cell(std::string(value));
    }
    else
    {
        static_assert(!std::is_same_v<M, M>,
                      "这个类型不能落库：支持 bool / 整数 / 浮点 / 枚举 / string / string_view / Cell");
        return Cell();
    }
}

/**
 * @brief 把一个可选成员值转成单元格。
 * @param value 成员值。
 * @return 单元格；空可选值为 NULL。
 */
template <class M>
Cell toCell(const std::optional<M> &value)
{
    if (!value.has_value()) return Cell();
    return toCell(*value);
}

/**
 * @brief 把单元格写回成员。
 * @param cell 单元格。
 * @param out 目标成员。
 * @return 类型兼容返回 true。
 */
template <class M>
bool fromCell(const Cell &cell, M &out)
{
    if constexpr (std::is_same_v<M, Cell>)
    {
        out = cell;
        return true;
    }
    else if constexpr (std::is_same_v<M, bool>)
    {
        const std::optional<bool> parsed = cell.asBool();
        if (!parsed.has_value()) return false;
        out = *parsed;
        return true;
    }
    else if constexpr (std::is_enum_v<M>)
    {
        const std::optional<std::int64_t> parsed = cell.asInt();
        if (!parsed.has_value()) return false;
        out = static_cast<M>(*parsed);
        return true;
    }
    else if constexpr (std::is_integral_v<M>)
    {
        const std::optional<std::int64_t> parsed = cell.asInt();
        if (!parsed.has_value()) return false;
        out = static_cast<M>(*parsed);
        return true;
    }
    else if constexpr (std::is_floating_point_v<M>)
    {
        const std::optional<double> parsed = cell.asDouble();
        if (!parsed.has_value()) return false;
        out = static_cast<M>(*parsed);
        return true;
    }
    else if constexpr (std::is_same_v<M, std::string>)
    {
        if (cell.isNull()) return false;
        out = cell.toText();
        return true;
    }
    else
    {
        static_assert(!std::is_same_v<M, M>, "这个类型不能从数据库读回：见 toCell 的说明");
        return false;
    }
}

/**
 * @brief 把单元格写回可选成员。
 * @param cell 单元格。
 * @param out 目标成员。
 * @return 类型兼容返回 true。
 */
template <class M>
bool fromCell(const Cell &cell, std::optional<M> &out)
{
    if (cell.isNull())
    {
        out.reset();
        return true;
    }
    M inner{};
    if (!fromCell(cell, inner)) return false;
    out = std::move(inner);
    return true;
}

// ---------------------------------------------------------------------------
//  字段声明
// ---------------------------------------------------------------------------

/**
 * @brief 一个字段的声明：名字 + 成员指针 + 语义。
 *
 * @tparam T 模型类。
 * @tparam M 成员类型。
 */
template <class T, class M>
struct Field
{
    /** 成员类型（宏里要靠它来统一各字段的类型）。 */
    using member_type = M;

    FieldType type = FieldType::String; ///< 语义类型。

    std::string_view name;   ///< 字段名（条件、排序、诊断里用）。
    std::string_view column; ///< 列名；为空时等于 `name`。
    std::string_view text;   ///< 显示名；为空时等于 `name`。

    M T::*member = nullptr; ///< 成员指针。

    bool fixed = false;                ///< 禁止写入。
    bool visible = true;               ///< 是否在界面可见。
    int order = 0;                     ///< 显示顺序。
    bool useRelatedForeignKey = false; ///< 聚合时是否借子表指向本表的外键。
    SqlFunction function = SqlFunction::Count; ///< 聚合函数。
    const ModelMeta *related = nullptr;        ///< 关联 / 聚合的目标模型。

    /**
     * @brief 实际列名。
     * @return 显式列名，未指定时为字段名。
     */
    [[nodiscard]] std::string_view columnOrName() const noexcept
    {
        return column.empty() ? name : column;
    }

    /**
     * @brief 实际显示名。
     * @return 显式显示名，未指定时为字段名。
     */
    [[nodiscard]] std::string_view textOrName() const noexcept
    {
        return text.empty() ? name : text;
    }

    /**
     * @brief 读实例上的该字段。
     * @param instance 实例。
     * @return 单元格。
     */
    [[nodiscard]] Cell readCell(const T &instance) const { return ipage::toCell(instance.*member); }

    /**
     * @brief 把单元格写回实例。
     * @param instance 实例。
     * @param cell 单元格。
     * @return 类型兼容返回 true。
     */
    bool writeCell(T &instance, const Cell &cell) const
    {
        return ipage::fromCell(cell, instance.*member);
    }

    /**
     * @brief 是否应该出现在建表语句里。
     * @return 是真实列返回 true。
     */
    [[nodiscard]] constexpr bool isColumn() const noexcept { return ipage::isColumn(type); }

    /**
     * @brief 是否参与 INSERT / UPDATE。
     * @return 可写入返回 true。
     */
    [[nodiscard]] constexpr bool isWritable() const noexcept
    {
        return ipage::isWritable(type) && !fixed;
    }
};

// ---------------------------------------------------------------------------
//  字段构造器
// ---------------------------------------------------------------------------

/**
 * @brief 主键字段（不可写）。
 * @param name 字段名。
 * @param member 成员指针。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> id(std::string_view name, M T::*member, std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::ModelId;
    one.name = name;
    one.column = column;
    one.member = member;
    one.fixed = true;
    return one;
}

/**
 * @brief 外键字段（本表上真实存在的列）。
 * @param name 字段名。
 * @param member 成员指针。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> foreignKey(std::string_view name, M T::*member,
                                 std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::AssociationId;
    one.name = name;
    one.column = column;
    one.member = member;
    return one;
}

/**
 * @brief 外键字段，并记住目标模型。
 * @param name 字段名。
 * @param member 成员指针。
 * @param related 目标模型元数据。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> foreignKey(std::string_view name, M T::*member, const ModelMeta &related,
                                 std::string_view column = {}) noexcept
{
    Field<T, M> one = foreignKey(name, member, column);
    one.related = &related;
    return one;
}

/**
 * @brief 关联对象字段（不落库，查询时 LEFT JOIN 填充）。
 * @param name 字段名。
 * @param member 成员指针。
 * @param related 目标模型元数据。
 * @param column JOIN 用的列（一般与对应外键一致）。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> association(std::string_view name, M T::*member, const ModelMeta &related,
                                  std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::AssociationModel;
    one.name = name;
    one.column = column.empty() ? name : column;
    one.member = member;
    one.related = &related;
    return one;
}

/**
 * @brief 文本字段。
 * @param name 字段名。
 * @param member 成员指针。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> text(std::string_view name, M T::*member, std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::String;
    one.name = name;
    one.column = column;
    one.member = member;
    return one;
}

/**
 * @brief 数值字段。
 * @param name 字段名。
 * @param member 成员指针。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> number(std::string_view name, M T::*member, std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::Number;
    one.name = name;
    one.column = column;
    one.member = member;
    return one;
}

/**
 * @brief 枚举字段（落库为整数）。
 * @param name 字段名。
 * @param member 成员指针。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> enumeration(std::string_view name, M T::*member,
                                  std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::Enum;
    one.name = name;
    one.column = column;
    one.member = member;
    return one;
}

/**
 * @brief 日期字段（落库为 `YYYY-MM-DD` 文本）。
 * @param name 字段名。
 * @param member 成员指针。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> date(std::string_view name, M T::*member, std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::Date;
    one.name = name;
    one.column = column;
    one.member = member;
    return one;
}

/**
 * @brief 布尔字段。
 * @param name 字段名。
 * @param member 成员指针。
 * @param column 列名，留空取字段名。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> flag(std::string_view name, M T::*member, std::string_view column = {}) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::Boolean;
    one.name = name;
    one.column = column;
    one.member = member;
    return one;
}

/**
 * @brief 聚合字段。
 * @param name 字段名。
 * @param member 成员指针。
 * @param related 被聚合的模型元数据。
 * @param column 被聚合的列名。
 * @param function 聚合函数。
 * @param useRelatedForeignKey true 表示借「子表指向本表的外键」关联。
 * @return 字段声明。
 */
template <class T, class M>
constexpr Field<T, M> aggregate(std::string_view name, M T::*member, const ModelMeta &related,
                                std::string_view column, SqlFunction function = SqlFunction::Count,
                                bool useRelatedForeignKey = false) noexcept
{
    Field<T, M> one{};
    one.type = FieldType::Aggregation;
    one.name = name;
    one.column = column;
    one.member = member;
    one.function = function;
    one.useRelatedForeignKey = useRelatedForeignKey;
    one.related = &related;
    return one;
}

/**
 * @brief 修改显示属性。
 * @param one 字段声明。
 * @param text 显示名。
 * @param order 顺序。
 * @param visible 是否可见。
 * @return 改动后的字段声明。
 */
template <class T, class M>
constexpr Field<T, M> label(Field<T, M> one, std::string_view text, int order = 0,
                            bool visible = true) noexcept
{
    one.text = text;
    one.order = order;
    one.visible = visible;
    return one;
}

/**
 * @brief 标记为不可写。
 * @param one 字段声明。
 * @return 改动后的字段声明。
 */
template <class T, class M>
constexpr Field<T, M> readonly(Field<T, M> one) noexcept
{
    one.fixed = true;
    return one;
}

// ---------------------------------------------------------------------------
//  类型擦除的字段描述
// ---------------------------------------------------------------------------

/**
 * @brief 运行期的字段描述（类型无关）。
 *
 * 由 `IPAGE_MODEL` 在注册时从 `Field<T, M>` 生成：成员指针被包成两个 `std::function`，
 * 于是 `Query`、`SqlStatement`、`Session` 都不必是模板。
 */
struct FieldInfo
{
    std::string name;   ///< 字段名。
    std::string column; ///< 列名。
    std::string text;   ///< 显示名。

    FieldType type = FieldType::String; ///< 语义类型。
    bool fixed = false;                 ///< 禁止写入。
    bool visible = true;                ///< 是否可见。
    int order = 0;                      ///< 顺序。
    bool useRelatedForeignKey = false;  ///< 聚合时是否借外键。
    SqlFunction function = SqlFunction::Count; ///< 聚合函数。

    const ModelMeta *related = nullptr; ///< 关联 / 聚合的目标模型。

    /** 读一个实例上的该字段。 */
    std::function<Cell(const void *)> read;

    /** 把单元格写回实例；类型不兼容返回 false。 */
    std::function<bool(void *, const Cell &)> write;

    /**
     * @brief 是否落库。
     * @return 是真实列返回 true。
     */
    [[nodiscard]] bool isColumn() const noexcept { return ipage::isColumn(type); }

    /**
     * @brief 是否参与写入。
     * @return 可写入返回 true。
     */
    [[nodiscard]] bool isWritable() const noexcept
    {
        return ipage::isWritable(type) && !fixed;
    }
};

/**
 * @brief 一个模型的元数据（类型无关）。
 *
 * 由 `metaOf<T>()` 建立一次，之后所有查询共用。
 */
class ModelMeta
{
public:
    /**
     * @brief 构造。
     * @param name 显示名。
     * @param table 表名。
     */
    ModelMeta(std::string name, std::string table) : name_(std::move(name)), table_(std::move(table))
    {
    }

    /** 显示名。 */
    [[nodiscard]] const std::string &name() const noexcept { return name_; }
    /** 表名。 */
    [[nodiscard]] const std::string &table() const noexcept { return table_; }

    /** 字段表。 */
    [[nodiscard]] const std::vector<FieldInfo> &fields() const noexcept { return fields_; }

    /**
     * @brief 追加一个字段。
     * @param info 字段描述。
     */
    void add(FieldInfo info) { fields_.push_back(std::move(info)); }

    /**
     * @brief 校验并定稿（建立主键索引）。
     * @throws std::invalid_argument 没有字段或没有主键。
     */
    void seal()
    {
        if (fields_.empty())
        {
            throw std::invalid_argument("模型 " + name_ + " 没有声明任何字段");
        }
        for (std::size_t index = 0; index < fields_.size(); ++index)
        {
            if (fields_[index].type == FieldType::ModelId)
            {
                idIndex_ = index;
                break;
            }
        }
        if (!idIndex_.has_value())
        {
            throw std::invalid_argument("模型 " + name_ + " 没有声明主键（用 ipage::id(...)）");
        }
    }

    /** 主键字段序号。 */
    [[nodiscard]] std::size_t idIndex() const noexcept { return *idIndex_; }
    /** 主键字段。 */
    [[nodiscard]] const FieldInfo &idField() const { return fields_.at(*idIndex_); }
    /** 主键列名。 */
    [[nodiscard]] const std::string &idColumn() const noexcept { return idField().column; }

    /**
     * @brief 按字段名或列名解析字段序号。
     * @param nameOrColumn 字段名或列名。
     * @return 序号；找不到为空。
     */
    [[nodiscard]] std::optional<std::size_t> indexOf(std::string_view nameOrColumn) const noexcept
    {
        for (std::size_t index = 0; index < fields_.size(); ++index)
        {
            if (fields_[index].name == nameOrColumn || fields_[index].column == nameOrColumn)
            {
                return index;
            }
        }
        return std::nullopt;
    }

    /**
     * @brief 按字段名或列名取字段。
     * @param nameOrColumn 字段名或列名。
     * @return 字段；找不到为空。
     */
    [[nodiscard]] const FieldInfo *field(std::string_view nameOrColumn) const noexcept
    {
        const std::optional<std::size_t> index = indexOf(nameOrColumn);
        if (!index.has_value()) return nullptr;
        return &fields_[*index];
    }

    /**
     * @brief 读出实例的主键。
     * @param instance 实例。
     * @return 主键单元格。
     */
    [[nodiscard]] Cell idOf(const void *instance) const { return idField().read(instance); }

    /**
     * @brief 诊断用：`学生(student)`。
     * @return 文本。
     */
    [[nodiscard]] std::string toString() const { return name_ + "(" + table_ + ")"; }

private:
    std::string name_;              ///< 显示名。
    std::string table_;             ///< 表名。
    std::vector<FieldInfo> fields_; ///< 字段表。
    std::optional<std::size_t> idIndex_; ///< 主键序号。
};

namespace detail
{

/**
 * @brief 把编译期的字段声明转成运行期的字段描述。
 * @param one 字段声明。
 * @return 字段描述。
 */
template <class T, class M>
FieldInfo erase(const Field<T, M> &one)
{
    FieldInfo info;
    // 显式转成 std::string：字符串字面量在 constexpr 上下文里只能拿到 string_view
    info.name = std::string(one.name);
    info.column = std::string(one.columnOrName());
    info.text = std::string(one.textOrName());
    info.type = one.type;
    info.fixed = one.fixed;
    info.visible = one.visible;
    info.order = one.order;
    info.useRelatedForeignKey = one.useRelatedForeignKey;
    info.function = one.function;
    info.related = one.related;
    info.read = [member = one.member](const void *instance) {
        return ipage::toCell(static_cast<const T *>(instance)->*member);
    };
    info.write = [member = one.member](void *instance, const Cell &cell) {
        return ipage::fromCell(cell, static_cast<T *>(instance)->*member);
    };
    return info;
}

} // namespace detail

/**
 * @brief 模型的绑定表（由 `IPAGE_MODEL` 特化）。
 *
 * 没有特化时 `metaOf<T>()` 会给出明确的编译期提示。
 *
 * @tparam T 模型类。
 */
template <class T, class = void>
struct Binding
{
    /** 没有声明。 */
    static constexpr bool declared = false;
};

/**
 * @brief 判断一个类型是否声明了 `IPAGE_MODEL`。
 */
template <class T, class = void>
struct hasBinding : std::false_type
{
};

/** 有 `Binding<T>::fields()` 时为 true。 */
template <class T>
struct hasBinding<T, std::void_t<decltype(Binding<T>::fields())>> : std::true_type
{
};

namespace detail
{

/**
 * @brief 把一个模型的全部字段擦成运行期描述。
 * @param name 显示名。
 * @param table 表名。
 * @param fields 字段元组。
 * @return 元数据。
 */
template <class T, class Tuple>
ModelMeta buildMeta(std::string_view name, std::string_view table, const Tuple &fields)
{
    ModelMeta meta{std::string(name), std::string(table)};
    std::apply([&meta](const auto &...items) { (meta.add(detail::erase<T>(items)), ...); }, fields);
    meta.seal();
    return meta;
}

} // namespace detail

/**
 * @brief 取一个模型的元数据。
 * @tparam T 模型类。
 * @return 元数据引用（静态生命周期，首次调用时建立）。
 */
template <class T>
const ModelMeta &metaOf()
{
    static_assert(hasBinding<std::decay_t<T>>::value || !std::is_same_v<T, T>,
                  "这个类型没有 IPAGE_MODEL(...) 绑定，无法当模型用");
    static const ModelMeta meta =
        detail::buildMeta<std::decay_t<T>>(Binding<std::decay_t<T>>::name(), Binding<std::decay_t<T>>::table(),
                                           Binding<std::decay_t<T>>::fields());
    return meta;
}

} // namespace ipage

/**
 * @brief 声明一个模型的字段表（表名默认由类名转换）。
 *
 * 展开成 `ipage::Binding<T>` 的显式特化，**只能写在全局作用域**。
 * 类型本身可以在命名空间里，写全限定名即可。
 */
#define IPAGE_MODEL(Type, ...)                                                                \
    template <>                                                                               \
    struct ipage::Binding<Type>                                                               \
    {                                                                                         \
        static constexpr std::string_view name() { return #Type; }                             \
        static std::string table() { return ipage::detail::toSnakeCase(#Type); }                \
        static auto fields() { return std::make_tuple(__VA_ARGS__); }                          \
    }

/**
 * @brief 声明一个模型的字段表（显式表名）。
 */
#define IPAGE_MODEL_AS(Type, Table, ...)                                                      \
    template <>                                                                               \
    struct ipage::Binding<Type>                                                               \
    {                                                                                         \
        static constexpr std::string_view name() { return #Type; }                             \
        static std::string table() { return std::string(Table); }                              \
        static auto fields() { return std::make_tuple(__VA_ARGS__); }                          \
    }

#endif // IPAGE_MODEL_HPP
