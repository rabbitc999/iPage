/**
 * @file schema.hpp
 * @brief `SchemaSync`：按模型的字段表建表 / 补列。
 *
 * 对应 Java 版的 `SchemaSync`。只做两件保守的事：
 *
 *   1. 表不存在 → `CREATE TABLE`（主键为 `ModelId` 字段，自增）。
 *   2. 表存在但缺列 → `ALTER TABLE ... ADD COLUMN`。
 *
 * **不删列、不改类型、不加约束**——这些在线上库上是高危操作，改由人来做。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#ifndef IPAGE_SCHEMA_HPP
#define IPAGE_SCHEMA_HPP

#include "connection.hpp"
#include "dialect.hpp"
#include "field.hpp"
#include "model.hpp"

#include <map>
#include <string>
#include <type_traits>
#include <vector>

namespace ipage
{

namespace detail
{

/**
 * @brief 成员类型的粗分类（供方言选列类型）。
 * @tparam M 成员类型。
 * @return 分类。
 */
template <class M>
constexpr ColumnKind kindOf() noexcept
{
    using raw = std::decay_t<M>;
    if constexpr (std::is_same_v<raw, bool>)
    {
        return ColumnKind::Boolean;
    }
    else if constexpr (std::is_enum_v<raw>)
    {
        return ColumnKind::Integer;
    }
    else if constexpr (std::is_integral_v<raw>)
    {
        return ColumnKind::Integer;
    }
    else if constexpr (std::is_floating_point_v<raw>)
    {
        return ColumnKind::Real;
    }
    else
    {
        return ColumnKind::Text;
    }
}

/**
 * @brief 一个模型在 `ColumnKind` 之下的列分类（按字段序号）。
 *
 * `FieldInfo` 是类型擦除的，丢掉了成员的 C++ 类型；这里在声明期把分类算出来，
 * 供建表时选择 SQL 类型。
 *
 * @tparam T 模型类型。
 * @return 与 `metaOf<T>().fields()` 同序的分类表。
 */
template <class T>
std::vector<int> columnKinds()
{
    std::vector<int> result;
    const auto fields = Binding<T>::fields();
    std::apply(
        [&result](const auto &...items) {
            (result.push_back(static_cast<int>(kindOf<typename std::decay_t<decltype(items)>::member_type>())),
             ...);
        },
        fields);
    return result;
}

} // namespace detail

/**
 * @brief 建表 / 补列。
 */
class SchemaSync
{
public:
    /**
     * @brief 构造。
     * @param connection 连接。
     */
    explicit SchemaSync(Connection &connection) : connection_(&connection) {}

    /**
     * @brief 同步一个模型对应的表。
     * @tparam T 模型类型。
     * @return 实际执行过的 DDL 列表（空表示已是最新）。
     */
    template <class T>
    std::vector<std::string> sync()
    {
        using Model = std::decay_t<T>;
        static_assert(hasBinding<Model>::value, "这个类型没有 IPAGE_MODEL(...) 绑定");

        const ModelMeta &meta = metaOf<Model>();
        const Dialect &dialect = connection_->dialect();
        const std::vector<int> kinds = detail::columnKinds<Model>();
        std::vector<std::string> applied;

        if (!connection_->hasTable(meta.table()))
        {
            applied.push_back(createTable(meta, kinds, dialect));
            connection_->execute(applied.back());
            return applied;
        }

        const std::map<std::string, std::string> existing = connection_->columns(meta.table());
        for (std::size_t index = 0; index < meta.fields().size(); ++index)
        {
            const FieldInfo &info = meta.fields()[index];
            if (!info.isColumn()) continue;
            if (existing.find(info.column) != existing.end()) continue;

            const int kind = index < kinds.size() ? kinds[index] : static_cast<int>(ColumnKind::Text);
            std::string sql = "ALTER TABLE ";
            sql += dialect.quote(meta.table());
            sql += " ADD COLUMN ";
            sql += dialect.quote(info.column);
            sql += ' ';
            sql += dialect.columnType(static_cast<int>(info.type), kind);
            applied.push_back(std::move(sql));
            connection_->execute(applied.back());
        }
        return applied;
    }

    /**
     * @brief 生成建表语句（不执行）。
     * @tparam T 模型类型。
     * @return 语句文本。
     */
    template <class T>
    [[nodiscard]] std::string createTableSql() const
    {
        using Model = std::decay_t<T>;
        const ModelMeta &meta = metaOf<Model>();
        return createTable(meta, detail::columnKinds<Model>(), connection_->dialect());
    }

private:
    /**
     * @brief 组装 `CREATE TABLE`。
     * @param meta 元数据。
     * @param kinds 列分类。
     * @param dialect 方言。
     * @return 语句。
     */
    static std::string createTable(const ModelMeta &meta, const std::vector<int> &kinds,
                                   const Dialect &dialect)
    {
        std::string sql = "CREATE TABLE ";
        sql += dialect.quote(meta.table());
        sql += " (";
        bool first = true;
        for (std::size_t index = 0; index < meta.fields().size(); ++index)
        {
            const FieldInfo &info = meta.fields()[index];
            if (!info.isColumn()) continue;
            const int kind = index < kinds.size() ? kinds[index] : static_cast<int>(ColumnKind::Text);
            if (!first) sql += ", ";
            first = false;
            sql += dialect.quote(info.column);
            sql += ' ';
            sql += dialect.columnType(static_cast<int>(info.type), kind);
            if (info.type == FieldType::ModelId)
            {
                sql += " PRIMARY KEY";
                if (kind == static_cast<int>(ColumnKind::Integer))
                {
                    sql += " AUTOINCREMENT";
                }
            }
        }
        sql += ')';
        return sql;
    }

    Connection *connection_ = nullptr;
};

} // namespace ipage

#endif // IPAGE_SCHEMA_HPP
