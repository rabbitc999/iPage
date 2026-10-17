/**
 * @file dialect.hpp
 * @brief 数据库方言：标识符引号、占位符、建表语句、分页语法。
 *
 * 对应 Java 版的 `dialect/Dialect`、`dialect/DefaultDialect`、`dialect/SqliteDialect`。
 *
 * 这一层只做**字符串拼接**，不碰连接、不碰参数绑定。所有 SQL 都经由 `Dialect`
 * 生成，因此换数据库只需要换一个方言实现，`Query` / `Session` 一行不改。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#ifndef IPAGE_DIALECT_HPP
#define IPAGE_DIALECT_HPP

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace ipage
{

/**
 * @brief 数据库方言接口。
 *
 * 派生类只需覆盖有差异的方法；默认实现按标准 SQL / SQLite 的写法给出。
 */
class Dialect
{
public:
    virtual ~Dialect() = default;

    /** @brief 方言名（`sqlite` / `mysql` / `postgresql`）。 */
    [[nodiscard]] virtual std::string_view name() const noexcept { return "default"; }

    /**
     * @brief 给标识符加引号。
     * @param identifier 表名或列名（不含引号）。
     * @return 加过引号的标识符。
     */
    [[nodiscard]] virtual std::string quote(std::string_view identifier) const
    {
        return "\"" + std::string(identifier) + "\"";
    }

    /**
     * @brief 第 `index` 个占位符（从 0 开始）。
     * @param index 参数下标。
     * @return 占位符文本。
     */
    [[nodiscard]] virtual std::string placeholder(std::size_t /*index*/) const { return "?"; }

    /**
     * @brief 拼接 `表名.列名`，两边都加引号。
     * @param table 表名或表别名。
     * @param column 列名。
     * @return 限定列名。
     */
    [[nodiscard]] virtual std::string qualify(std::string_view table, std::string_view column) const
    {
        return quote(table) + "." + quote(column);
    }

    /**
     * @brief 生成分页子句，拼在 `ORDER BY` 之后。
     * @param limit 每页条数。
     * @param offset 跳过条数。
     * @return 子句（自带前导空格），`limit <= 0` 时返回空串。
     */
    [[nodiscard]] virtual std::string limitClause(long long limit, long long offset) const
    {
        if (limit <= 0)
        {
            return {};
        }
        std::string clause = " LIMIT " + std::to_string(limit);
        if (offset > 0)
        {
            clause += " OFFSET " + std::to_string(offset);
        }
        return clause;
    }

    /**
     * @brief 布尔字面量。
     * @param value 布尔值。
     * @return 该方言下的布尔字面量。
     */
    [[nodiscard]] virtual std::string booleanLiteral(bool value) const
    {
        return value ? "1" : "0";
    }

    /**
     * @brief 建表语句的列类型映射。
     * @param fieldType 字段类型（`ipage::FieldType` 的整数值）。
     * @param memberSize 成员类型在库内的分类，见 `ColumnKind`。
     * @return 列类型文本，例如 `TEXT`、`INTEGER`、`REAL`。
     */
    [[nodiscard]] virtual std::string columnType(int fieldType, int memberKind) const;
};

/**
 * @brief 成员类型的粗分类，供方言选择列类型。
 *
 * 与 `FieldType`（业务语义）正交：`FieldType::Number` 既可能是 `int` 也可能是
 * `double`，列的 SQL 类型要按 **C++ 成员类型**决定。
 */
enum class ColumnKind
{
    Integer, ///< 整型。
    Real,    ///< 浮点型。
    Text,    ///< 字符串。
    Boolean, ///< 布尔。
};

/**
 * @brief SQLite 方言。
 *
 * SQLite 的引号是双引号、占位符是 `?`、分页是 `LIMIT x OFFSET y`，
 * 与 `Dialect` 默认实现一致，这里只覆写类型映射和方言名。
 */
class SqliteDialect final : public Dialect
{
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "sqlite"; }

    [[nodiscard]] std::string quote(std::string_view identifier) const override
    {
        return "`" + std::string(identifier) + "`";
    }

    [[nodiscard]] std::string columnType(int fieldType, int memberKind) const override;
};

/**
 * @brief 标准 SQL 方言（双引号 + `?` 占位符）。
 */
class DefaultDialect final : public Dialect
{
public:
    [[nodiscard]] std::string_view name() const noexcept override { return "default"; }
};

/**
 * @brief 取进程级的默认方言（SQLite）。
 * @return 方言引用，静态生命周期。
 */
[[nodiscard]] const Dialect &defaultDialect();

/**
 * @brief 按名字取方言。
 * @param name 方言名（`sqlite` / `default`），不区分大小写。
 * @return 方言指针，未知名返回 `nullptr`。
 */
[[nodiscard]] const Dialect *findDialect(std::string_view name) noexcept;

} // namespace ipage

#endif // IPAGE_DIALECT_HPP
