/**
 * @file dialect.cpp
 * @brief `Dialect` 的列类型映射与方言注册表。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#include "dialect.hpp"

#include "field.hpp"

#include <algorithm>
#include <cctype>

namespace ipage
{

std::string Dialect::columnType(int fieldType, int memberKind) const
{
    const auto type = static_cast<FieldType>(fieldType);
    const auto kind = static_cast<ColumnKind>(memberKind);
    switch (type)
    {
    case FieldType::ModelId:
    case FieldType::AssociationId:
        return "INTEGER";
    case FieldType::Boolean:
        return "INTEGER";
    case FieldType::Date:
    case FieldType::String:
    case FieldType::Enum:
        return "TEXT";
    case FieldType::AssociationModel:
    case FieldType::Aggregation:
        // 关联 / 聚合字段不入库，调用方不应走到这里；给个安全的兜底。
        return "TEXT";
    case FieldType::Number:
        break;
    }
    switch (kind)
    {
    case ColumnKind::Integer:
    case ColumnKind::Boolean:
        return "INTEGER";
    case ColumnKind::Real:
        return "REAL";
    case ColumnKind::Text:
        break;
    }
    return "TEXT";
}

std::string SqliteDialect::columnType(int fieldType, int memberKind) const
{
    const auto type = static_cast<FieldType>(fieldType);
    if (type == FieldType::ModelId || type == FieldType::AssociationId)
    {
        return "INTEGER";
    }
    if (type == FieldType::Boolean)
    {
        return "INTEGER";
    }
    if (type == FieldType::Number)
    {
        return memberKind == static_cast<int>(ColumnKind::Integer) ? "INTEGER" : "REAL";
    }
    return "TEXT";
}

const Dialect &defaultDialect()
{
    static const SqliteDialect dialect;
    return dialect;
}

const Dialect *findDialect(std::string_view name) noexcept
{
    static const SqliteDialect sqlite;
    static const DefaultDialect standard;

    std::string lowered(name);
    std::transform(lowered.begin(), lowered.end(), lowered.begin(),
                   [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });

    if (lowered == "sqlite" || lowered == "sqlite3")
    {
        return &sqlite;
    }
    if (lowered == "default" || lowered == "standard" || lowered == "ansi")
    {
        return &standard;
    }
    return nullptr;
}

} // namespace ipage
