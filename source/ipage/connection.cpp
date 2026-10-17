/**
 * @file connection.cpp
 * @brief `Cursor` / `Statement` / `Connection` 的公共实现与 SQLite 驱动。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#include "connection.hpp"

#include "dialect.hpp"

#include <sqlite3.h>

#include <algorithm>

namespace ipage
{

// ---------------------------------------------------------------------------
//  Cursor / Statement 的公共默认实现
// ---------------------------------------------------------------------------

Cell Cursor::value(std::string_view name) const
{
    const std::size_t count = columnCount();
    for (std::size_t index = 0; index < count; ++index)
    {
        if (columnName(index) == name)
        {
            return value(index);
        }
    }
    throw DbError("结果里没有列 " + std::string(name));
}

void Statement::bindAll(const std::vector<Cell> &values)
{
    for (std::size_t index = 0; index < values.size(); ++index)
    {
        bind(index, values[index]);
    }
}

// ---------------------------------------------------------------------------
//  SQLite 实现
// ---------------------------------------------------------------------------

namespace
{

/** SQLite 方言（进程级单例）。 */
const Dialect &sqliteDialect()
{
    return defaultDialect();
}

/**
 * @brief SQLite 错误文本。
 * @param db 数据库句柄。
 * @return 文本。
 */
std::string lastError(sqlite3 *db)
{
    return db == nullptr ? "SQLite 未知错误" : std::string(sqlite3_errmsg(db));
}

/** SQLite 语句句柄的删除器。 */
struct StatementReleaser
{
    /** 释放。 */
    void operator()(sqlite3_stmt *handle) const noexcept
    {
        if (handle != nullptr)
        {
            sqlite3_finalize(handle);
        }
    }
};

using StatementHandle = std::unique_ptr<sqlite3_stmt, StatementReleaser>;

/** SQLite 结果集。 */
class SqliteCursor final : public Cursor
{
public:
    SqliteCursor(sqlite3 *db, StatementHandle handle, std::string sql)
        : db_(db), handle_(std::move(handle)), sql_(std::move(sql))
    {
        const int count = sqlite3_column_count(handle_.get());
        names_.reserve(static_cast<std::size_t>(count));
        for (int index = 0; index < count; ++index)
        {
            const char *name = sqlite3_column_name(handle_.get(), index);
            names_.emplace_back(name == nullptr ? "" : name);
        }
    }

    bool next() override
    {
        const int code = sqlite3_step(handle_.get());
        if (code == SQLITE_ROW)
        {
            return true;
        }
        if (code == SQLITE_DONE)
        {
            return false;
        }
        throw DbError("读取结果失败：" + lastError(db_), sql_);
    }

    [[nodiscard]] std::size_t columnCount() const noexcept override { return names_.size(); }

    [[nodiscard]] std::string columnName(std::size_t index) const override
    {
        if (index >= names_.size())
        {
            throw DbError("列序号越界：" + std::to_string(index), sql_);
        }
        return names_[index];
    }

    [[nodiscard]] Cell value(std::size_t index) const override
    {
        sqlite3_stmt *handle = handle_.get();
        if (index >= names_.size())
        {
            throw DbError("列序号越界：" + std::to_string(index), sql_);
        }
        const int column = static_cast<int>(index);
        switch (sqlite3_column_type(handle, column))
        {
        case SQLITE_NULL:
            return Cell();
        case SQLITE_INTEGER:
            return Cell(static_cast<std::int64_t>(sqlite3_column_int64(handle, column)));
        case SQLITE_FLOAT:
            return Cell(sqlite3_column_double(handle, column));
        case SQLITE_BLOB:
        case SQLITE_TEXT:
        {
            const auto *text = sqlite3_column_text(handle, column);
            const int size = sqlite3_column_bytes(handle, column);
            if (text == nullptr)
            {
                return Cell();
            }
            return Cell(std::string(reinterpret_cast<const char *>(text),
                                    static_cast<std::size_t>(size)));
        }
        }
        return Cell();
    }

private:
    sqlite3 *db_ = nullptr;
    StatementHandle handle_;
    std::string sql_;
    std::vector<std::string> names_;
};

/** SQLite 预编译语句。 */
class SqliteStatement final : public Statement
{
public:
    SqliteStatement(sqlite3 *db, StatementHandle handle, std::string sql)
        : db_(db), handle_(std::move(handle)), sql_(std::move(sql))
    {
    }

    void bind(std::size_t index, const Cell &value) override
    {
        sqlite3_stmt *handle = handle_.get();
        const int position = static_cast<int>(index) + 1;
        int code = SQLITE_OK;
        if (value.isNull())
        {
            code = sqlite3_bind_null(handle, position);
        }
        else if (value.isInt())
        {
            code = sqlite3_bind_int64(handle, position, *value.asInt());
        }
        else if (value.isDouble())
        {
            code = sqlite3_bind_double(handle, position, *value.asDouble());
        }
        else if (value.isBool())
        {
            code = sqlite3_bind_int(handle, position, *value.asBool() ? 1 : 0);
        }
        else
        {
            const std::string text = value.toText();
            code = sqlite3_bind_text(handle, position, text.c_str(),
                                     static_cast<int>(text.size()), SQLITE_TRANSIENT);
        }
        if (code != SQLITE_OK)
        {
            throw DbError("绑定第 " + std::to_string(index) + " 个参数失败：" + lastError(db_), sql_);
        }
    }

    std::size_t execute() override
    {
        const int code = sqlite3_step(handle_.get());
        if (code != SQLITE_DONE && code != SQLITE_ROW)
        {
            throw DbError("执行失败：" + lastError(db_), sql_);
        }
        return static_cast<std::size_t>(sqlite3_changes(db_));
    }

    std::unique_ptr<Cursor> query() override
    {
        sqlite3_reset(handle_.get());
        return std::make_unique<SqliteCursor>(db_, std::move(handle_), sql_);
    }

    [[nodiscard]] std::int64_t lastInsertId() override
    {
        return static_cast<std::int64_t>(sqlite3_last_insert_rowid(db_));
    }

private:
    sqlite3 *db_ = nullptr;
    StatementHandle handle_;
    std::string sql_;
};

} // namespace

std::unique_ptr<SqliteConnection> SqliteConnection::open(const std::string &path)
{
    sqlite3 *db = nullptr;
    const int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_URI;
    const int code = sqlite3_open_v2(path.c_str(), &db, flags, nullptr);
    if (code != SQLITE_OK)
    {
        const std::string message = db == nullptr ? "无法打开数据库" : sqlite3_errmsg(db);
        if (db != nullptr)
        {
            sqlite3_close(db);
        }
        throw DbError("打开 " + path + " 失败：" + message);
    }
    sqlite3_busy_timeout(db, 5000);
    // 外键约束默认关闭，显式打开（与 Java 版的假设一致）。
    sqlite3_exec(db, "PRAGMA foreign_keys = ON", nullptr, nullptr, nullptr);
    return std::unique_ptr<SqliteConnection>(new SqliteConnection(db, path));
}

namespace
{

/** 把公共接口的 `void *` 还原成 SQLite 句柄。 */
sqlite3 *asHandle(void *handle) noexcept
{
    return static_cast<sqlite3 *>(handle);
}

} // namespace

void *SqliteConnection::handle() const noexcept
{
    return db_;
}

SqliteConnection::SqliteConnection(void *db, std::string path)
    : db_(db), path_(std::move(path))
{
}

SqliteConnection::~SqliteConnection()
{
    if (db_ != nullptr)
    {
        sqlite3_close_v2(asHandle(db_));
        db_ = nullptr;
    }
}

std::unique_ptr<Statement> SqliteConnection::prepare(const std::string &sql)
{
    sqlite3_stmt *handle = nullptr;
    const int code =
        sqlite3_prepare_v2(asHandle(db_), sql.c_str(), static_cast<int>(sql.size()) + 1, &handle, nullptr);
    if (code != SQLITE_OK)
    {
        throw DbError("编译失败：" + lastError(asHandle(db_)), sql);
    }
    return std::make_unique<SqliteStatement>(asHandle(db_), StatementHandle(handle), sql);
}

std::size_t SqliteConnection::execute(const std::string &sql)
{
    const std::unique_ptr<Statement> statement = prepare(sql);
    return statement->execute();
}

void SqliteConnection::begin()
{
    execute("BEGIN");
}

void SqliteConnection::commit()
{
    execute("COMMIT");
}

void SqliteConnection::rollback()
{
    execute("ROLLBACK");
}

const Dialect &SqliteConnection::dialect() const
{
    return sqliteDialect();
}

bool SqliteConnection::hasTable(const std::string &table)
{
    const std::unique_ptr<Statement> statement =
        prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1");
    statement->bind(0, Cell(table));
    const std::unique_ptr<Cursor> cursor = statement->query();
    return cursor->next();
}

std::map<std::string, std::string> SqliteConnection::columns(const std::string &table)
{
    std::map<std::string, std::string> result;
    // PRAGMA 不支持参数绑定，表名只能拼进 SQL；这里只接受标识符字符。
    for (const char ch : table)
    {
        const bool safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                          (ch >= '0' && ch <= '9') || ch == '_';
        if (!safe)
        {
            throw DbError("表名含有非法字符：" + table);
        }
    }
    const std::unique_ptr<Statement> statement =
        prepare("PRAGMA table_info(" + dialect().quote(table) + ")");
    const std::unique_ptr<Cursor> cursor = statement->query();
    while (cursor->next())
    {
        const Cell name = cursor->value("name");
        const Cell type = cursor->value("type");
        const auto text = name.asText();
        if (!text.has_value()) continue;
        const auto typeText = type.asText();
        result.emplace(std::string(*text), typeText.has_value() ? std::string(*text) : std::string());
    }
    return result;
}

std::string SqliteConnection::describe() const
{
    return "sqlite:" + path_;
}

} // namespace ipage
