/**
 * @file connection.hpp
 * @brief 连接抽象：`Connection` / `Statement` / `Cursor`，以及内嵌 SQLite 实现。
 *
 * 对应 Java 版的 `jdbc` 包（`DataSource`、`Connection`、`PreparedStatement`、`ResultSet`）。
 *
 * ## 为什么要抽象
 *
 * `Session` 只认 `Connection` 接口，因此换 MySQL / PostgreSQL 只需新增一个派生类，
 * 内核代码（查询、装配、事务）一行不改。SQLite 只是**默认实现**，不是唯一实现。
 *
 * ## 类型绑定
 *
 * 参数与结果都走 `Cell`，刻意不用 `sqlite3_bind_text` 的临时指针：
 * 文本一律 `SQLITE_TRANSIENT`，由 SQLite 自己拷一份，生命周期不会踩坑。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#ifndef IPAGE_CONNECTION_HPP
#define IPAGE_CONNECTION_HPP

#include "field.hpp"

#include <cstddef>
#include <cstdint>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace ipage
{

// ---------------------------------------------------------------------------
//  异常
// ---------------------------------------------------------------------------

/**
 * @brief 数据库层异常。
 */
class DbError : public std::runtime_error
{
public:
    /**
     * @brief 构造。
     * @param message 描述。
     * @param sql 出错的语句（可为空）。
     */
    explicit DbError(const std::string &message, std::string sql = {})
        : std::runtime_error(message), sql_(std::move(sql))
    {
    }

    /**
     * @brief 出错的语句。
     * @return 语句文本。
     */
    [[nodiscard]] const std::string &sql() const noexcept { return sql_; }

private:
    std::string sql_;
};

// ---------------------------------------------------------------------------
//  游标
// ---------------------------------------------------------------------------

/**
 * @brief 一行结果，按列序号或列名取值。
 */
class Cursor
{
public:
    virtual ~Cursor() = default;

    /**
     * @brief 前进到下一行。
     * @return 还有行返回 true。
     */
    virtual bool next() = 0;

    /**
     * @brief 列数。
     * @return 列数。
     */
    [[nodiscard]] virtual std::size_t columnCount() const noexcept = 0;

    /**
     * @brief 第 `index` 列的名字。
     * @param index 列序号（从 0 开始）。
     * @return 列名。
     */
    [[nodiscard]] virtual std::string columnName(std::size_t index) const = 0;

    /**
     * @brief 取第 `index` 列的值。
     * @param index 列序号（从 0 开始）。
     * @return 单元格。
     */
    [[nodiscard]] virtual Cell value(std::size_t index) const = 0;

    /**
     * @brief 按列名取值。
     * @param name 列名。
     * @return 单元格；列不存在时抛 `DbError`。
     * @throws DbError 列不存在。
     */
    [[nodiscard]] virtual Cell value(std::string_view name) const;
};

// ---------------------------------------------------------------------------
//  语句 / 连接
// ---------------------------------------------------------------------------

/**
 * @brief 一条已编译的语句。
 */
class Statement
{
public:
    virtual ~Statement() = default;

    /**
     * @brief 绑定第 `index` 个参数（从 0 开始）。
     * @param index 参数序号。
     * @param value 值。
     */
    virtual void bind(std::size_t index, const Cell &value) = 0;

    /**
     * @brief 按顺序绑定全部参数。
     * @param values 参数列表。
     */
    virtual void bindAll(const std::vector<Cell> &values);

    /**
     * @brief 执行并丢弃结果（INSERT / UPDATE / DELETE / DDL）。
     * @return 受影响行数（DDL 返回 0）。
     */
    virtual std::size_t execute() = 0;

    /**
     * @brief 执行并返回游标（SELECT）。
     * @return 游标。
     */
    virtual std::unique_ptr<Cursor> query() = 0;

    /**
     * @brief 最后一次插入生成的主键。
     * @return 主键。
     */
    [[nodiscard]] virtual std::int64_t lastInsertId() = 0;
};

/**
 * @brief 一个数据库连接。
 *
 * 非线程安全：一个连接在同一时刻只应被一个线程使用。多线程请各自 `open`，
 * 或在外层加锁。
 */
class Connection
{
public:
    virtual ~Connection() = default;

    /**
     * @brief 编译一条语句。
     * @param sql 语句文本。
     * @return 语句对象。
     * @throws DbError 语法错误。
     */
    virtual std::unique_ptr<Statement> prepare(const std::string &sql) = 0;

    /**
     * @brief 直接执行一条不取结果的语句。
     * @param sql 语句文本。
     * @return 受影响行数。
     */
    virtual std::size_t execute(const std::string &sql) = 0;

    /**
     * @brief 开启事务。
     */
    virtual void begin() = 0;
    /**
     * @brief 提交事务。
     */
    virtual void commit() = 0;
    /**
     * @brief 回滚事务。
     */
    virtual void rollback() = 0;

    /**
     * @brief 方言。
     * @return 方言引用。
     */
    [[nodiscard]] virtual const class Dialect &dialect() const = 0;

    /**
     * @brief 表是否存在。
     * @param table 表名。
     * @return 存在返回 true。
     */
    [[nodiscard]] virtual bool hasTable(const std::string &table) = 0;

    /**
     * @brief 读取一张表的列信息。
     * @param table 表名。
     * @return 列名 → SQL 类型。
     */
    [[nodiscard]] virtual std::map<std::string, std::string> columns(const std::string &table) = 0;

    /**
     * @brief 诊断名。
     * @return 例如 `sqlite:demo.db`。
     */
    [[nodiscard]] virtual std::string describe() const = 0;
};

// ---------------------------------------------------------------------------
//  SQLite
// ---------------------------------------------------------------------------

/**
 * @brief 内嵌 SQLite 连接。
 *
 * 库文件为 `sqlite/sqlite3.c`，零外部依赖。
 */
class SqliteConnection final : public Connection
{
public:
    /**
     * @brief 打开（或创建）一个数据库文件。
     * @param path 文件路径；传 `:memory:` 打开内存库。
     * @return 连接。
     * @throws DbError 打开失败。
     */
    static std::unique_ptr<SqliteConnection> open(const std::string &path);

    ~SqliteConnection() override;

    SqliteConnection(const SqliteConnection &) = delete;
    SqliteConnection &operator=(const SqliteConnection &) = delete;

    std::unique_ptr<Statement> prepare(const std::string &sql) override;
    std::size_t execute(const std::string &sql) override;
    void begin() override;
    void commit() override;
    void rollback() override;

    [[nodiscard]] const class Dialect &dialect() const override;
    [[nodiscard]] bool hasTable(const std::string &table) override;
    [[nodiscard]] std::map<std::string, std::string> columns(const std::string &table) override;
    [[nodiscard]] std::string describe() const override;

    /** 底层句柄（要用 SQLite 独有能力时用；`void *` 是为了不把 sqlite3.h 带进公共头）。 */
    [[nodiscard]] void *handle() const noexcept;

private:
    explicit SqliteConnection(void *db, std::string path);

    void *db_ = nullptr;
    std::string path_;
};

} // namespace ipage

#endif // IPAGE_CONNECTION_HPP
