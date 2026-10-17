/**
 * @file session.hpp
 * @brief `Session<Model>`：增删改查 + 分页 + 事务 + 关联装配。
 *
 * 对应 Java 版的 `Session`、`ModelSession`、`SqlSession`。
 *
 * ## 与 Java 版的差别
 *
 * Java 用反射把 `ResultSet` 灌进对象；这里靠 `FieldInfo::write`（成员指针包成的
 * `std::function`）做同样的事。因此 `Session` 本身是模板（需要知道 `Model` 的静态类型），
 * 但内部所有 SQL 生成与执行都是**非模板**的。
 *
 * ## 典型用法
 *
 * ```cpp
 * ipage::Session<Student> students(*connection);
 * Student s{0, "张三", 20};
 * std::int64_t id = students.insert(s);       // 回填 s.id
 *
 * std::optional<Student> one = students.findById(id);
 * std::vector<Student> minors = students.selectList(
 *     ipage::Query(ipage::metaOf<Student>(), *connection->dialect())
 *         .filter("age", ipage::OperatorType::Lt, {ipage::Cell(18)}));
 * ```
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#ifndef IPAGE_SESSION_HPP
#define IPAGE_SESSION_HPP

#include "connection.hpp"
#include "dialect.hpp"
#include "model.hpp"
#include "sql.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <optional>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace ipage
{

/**
 * @brief 一页结果。
 * @tparam T 模型类型。
 */
template <class T>
struct Page
{
    std::vector<T> items;  ///< 当前页数据。
    long long total = 0;   ///< 总条数。
    long long page = 1;    ///< 当前页号（从 1 开始）。
    long long pageSize = 0;///< 每页条数。

    /** 总页数（`pageSize <= 0` 时为 0）。 */
    [[nodiscard]] long long pages() const noexcept
    {
        if (pageSize <= 0) return 0;
        return (total + pageSize - 1) / pageSize;
    }

    /** 是否还有下一页。 */
    [[nodiscard]] bool hasNext() const noexcept { return page < pages(); }
    /** 是否还有上一页。 */
    [[nodiscard]] bool hasPrevious() const noexcept { return page > 1; }
};

/**
 * @brief 某个模型的会话：绑定一个连接 + 一份元数据。
 * @tparam T 模型类型（必须已用 `IPAGE_MODEL` 绑定）。
 */
template <class T>
class Session
{
public:
    static_assert(hasBinding<std::decay_t<T>>::value,
                  "这个类型没有 IPAGE_MODEL(...) 绑定，不能当模型用");

    /** 模型类型。 */
    using Model = std::decay_t<T>;

    /**
     * @brief 构造。
     * @param connection 连接（生命周期由调用方保证）。
     */
    explicit Session(Connection &connection)
        : connection_(&connection), meta_(&metaOf<Model>())
    {
    }

    /** 连接。 */
    [[nodiscard]] Connection &connection() noexcept { return *connection_; }
    /** 元数据。 */
    [[nodiscard]] const ModelMeta &meta() const noexcept { return *meta_; }
    /** 方言。 */
    [[nodiscard]] const Dialect &dialect() const noexcept { return connection_->dialect(); }

    /**
     * @brief 造一个已配好元数据与方言的空查询。
     * @return 查询对象。
     */
    [[nodiscard]] Query query() const { return Query(*meta_, dialect()); }

    // -----------------------------------------------------------------------
    //  查询
    // -----------------------------------------------------------------------

    /**
     * @brief 按条件查询列表。
     * @param query 查询描述。
     * @return 结果列表。
     */
    [[nodiscard]] std::vector<Model> selectList(const Query &query) const
    {
        const SqlStatement statement = query.build();
        return runSelect(statement);
    }

    /**
     * @brief 查全部。
     * @return 结果列表。
     */
    [[nodiscard]] std::vector<Model> selectAll() const
    {
        Query built = query();
        return selectList(built);
    }

    /**
     * @brief 查询第一个结果。
     * @param query 查询描述。
     * @return 结果；没有则为空。
     */
    [[nodiscard]] std::optional<Model> selectOne(const Query &query) const
    {
        Query limited = query;
        limited.limit(1);
        std::vector<Model> items = selectList(limited);
        if (items.empty()) return std::nullopt;
        return std::move(items.front());
    }

    /**
     * @brief 按主键查一条。
     * @param id 主键值。
     * @return 结果；不存在为空。
     */
    [[nodiscard]] std::optional<Model> findById(const Cell &id) const
    {
        Query built = query();
        built.filter(meta_->idColumn(), OperatorType::Eq, {id});
        return selectOne(built);
    }

    /**
     * @brief 按主键批量查。
     * @param ids 主键列表。
     * @return 结果列表；`ids` 为空时返回空列表。
     */
    [[nodiscard]] std::vector<Model> findByIds(const std::vector<Cell> &ids) const
    {
        if (ids.empty()) return {};
        Query built = query();
        built.filter(meta_->idColumn(), OperatorType::In, ids);
        return selectList(built);
    }

    /**
     * @brief 计数。
     * @param query 查询描述（只用到条件）。
     * @return 条数。
     */
    [[nodiscard]] long long count(const Query &query) const
    {
        Query built = query;
        built.countOnly();
        const SqlStatement statement = built.build();
        const std::unique_ptr<Statement> prepared = connection_->prepare(statement.sql());
        prepared->bindAll(statement.params());
        const std::unique_ptr<Cursor> cursor = prepared->query();
        if (!cursor->next()) return 0;
        return cursor->value(0).asInt().value_or(0);
    }

    /**
     * @brief 计数（无条件）。
     * @return 条数。
     */
    [[nodiscard]] long long count() const
    {
        Query built = query();
        return count(built);
    }

    /**
     * @brief 是否存在满足条件的记录。
     * @param query 查询描述。
     * @return 存在返回 true。
     */
    [[nodiscard]] bool exists(const Query &query) const
    {
        Query built = query;
        built.countOnly();
        return count(built) > 0;
    }

    /**
     * @brief 分页查询（页号从 1 开始）。
     * @param query 查询描述（其中的 limit / offset 会被覆盖）。
     * @param page 页号。
     * @param pageSize 每页条数。
     * @return 一页结果（含总条数）。
     */
    [[nodiscard]] Page<Model> selectPage(const Query &query, long long page,
                                         long long pageSize) const
    {
        Page<Model> result;
        result.page = page < 1 ? 1 : page;
        result.pageSize = pageSize < 1 ? 1 : pageSize;

        Query counting = query;
        counting.countOnly();
        result.total = count(counting);

        Query paged = query;
        paged.limit(result.pageSize).offset((result.page - 1) * result.pageSize);
        result.items = selectList(paged);
        return result;
    }

    // -----------------------------------------------------------------------
    //  写入
    // -----------------------------------------------------------------------

    /**
     * @brief 插入一条，并把自增主键回填到实例。
     *
     * 主键字段的值若为 0 / 空串，视为「由数据库生成」：不写进 INSERT，
     * 插入后把 `lastInsertId()` 写回实例。否则按给定主键插入。
     *
     * @param model 实例。
     * @return 主键值。
     */
    std::int64_t insert(Model &model) const
    {
        const std::vector<const FieldInfo *> writable = writableFields();
        const Cell currentId = meta_->idOf(&model);
        const bool generatedId = isUnset(currentId);

        std::string sql = "INSERT INTO ";
        sql += dialect().quote(meta_->table());
        sql += " (";
        std::string placeholders;
        std::vector<Cell> params;
        bool first = true;
        for (const FieldInfo *info : writable)
        {
            if (generatedId && info->type == FieldType::ModelId) continue;
            if (!first)
            {
                sql += ", ";
                placeholders += ", ";
            }
            first = false;
            sql += dialect().quote(info->column);
            placeholders += dialect().placeholder(params.size());
            params.push_back(info->read(&model));
        }
        sql += ") VALUES (";
        sql += first ? "NULL" : placeholders;
        sql += ")";

        const std::unique_ptr<Statement> prepared = connection_->prepare(sql);
        prepared->bindAll(params);
        prepared->execute();
        const std::int64_t id = prepared->lastInsertId();

        if (generatedId)
        {
            const FieldInfo &idField = meta_->idField();
            if (idField.write != nullptr)
            {
                idField.write(&model, Cell(id));
            }
        }
        return id;
    }

    /**
     * @brief 按主键更新一条（跳过主键本身与只读字段）。
     * @param model 实例。
     * @return 受影响行数。
     */
    std::size_t update(const Model &model) const
    {
        const FieldInfo &idField = meta_->idField();
        const Cell id = idField.read(&model);
        if (isUnset(id))
        {
            throw DbError("更新 " + meta_->name() + " 时主键为空");
        }

        std::string sql = "UPDATE ";
        sql += dialect().quote(meta_->table());
        sql += " SET ";
        std::vector<Cell> params;
        bool first = true;
        for (const FieldInfo *info : writableFields())
        {
            if (info->type == FieldType::ModelId) continue;
            if (!first) sql += ", ";
            first = false;
            sql += dialect().quote(info->column);
            sql += " = ";
            sql += dialect().placeholder(params.size());
            params.push_back(info->read(&model));
        }
        if (first)
        {
            return 0; // 没有任何可更新列
        }
        sql += " WHERE ";
        sql += dialect().quote(idField.column);
        sql += " = ";
        sql += dialect().placeholder(params.size());
        params.push_back(id);

        const std::unique_ptr<Statement> prepared = connection_->prepare(sql);
        prepared->bindAll(params);
        return prepared->execute();
    }

    /**
     * @brief 按主键删除。
     * @param id 主键值。
     * @return 受影响行数。
     */
    std::size_t deleteById(const Cell &id) const
    {
        return deleteByIds({id});
    }

    /**
     * @brief 按主键批量删除。
     * @param ids 主键列表。
     * @return 受影响行数。
     */
    std::size_t deleteByIds(const std::vector<Cell> &ids) const
    {
        if (ids.empty()) return 0;
        std::string sql = "DELETE FROM ";
        sql += dialect().quote(meta_->table());
        sql += " WHERE ";
        sql += dialect().quote(meta_->idColumn());
        sql += " IN (";
        std::vector<Cell> params;
        for (std::size_t index = 0; index < ids.size(); ++index)
        {
            if (index > 0) sql += ", ";
            sql += dialect().placeholder(params.size());
            params.push_back(ids[index]);
        }
        sql += ")";

        const std::unique_ptr<Statement> prepared = connection_->prepare(sql);
        prepared->bindAll(params);
        return prepared->execute();
    }

    /**
     * @brief 按条件删除。
     * @param query 查询描述（只用到条件）。
     * @return 受影响行数。
     */
    std::size_t deleteWhere(const Query &query) const
    {
        std::string sql = "DELETE FROM ";
        sql += dialect().quote(meta_->table());
        sql += " AS ";
        sql += dialect().quote(Query::kMainAlias);
        std::vector<Cell> params;
        if (const Condition *condition = query.condition();
            condition != nullptr && !condition->isTrivial())
        {
            sql += " WHERE ";
            condition->render(sql, params, dialect(), Query::kMainAlias);
        }
        const std::unique_ptr<Statement> prepared = connection_->prepare(sql);
        prepared->bindAll(params);
        return prepared->execute();
    }

    // -----------------------------------------------------------------------
    //  事务
    // -----------------------------------------------------------------------

    /**
     * @brief 在一个事务里执行一段代码。
     *
     * 正常返回则提交；抛异常则回滚并把异常继续外抛。
     *
     * @tparam Fn 可调用类型。
     * @param body 事务体。
     * @return `body` 的返回值。
     */
    template <class Fn>
    auto transaction(Fn &&body) -> decltype(std::forward<Fn>(body)())
    {
        connection_->begin();
        try
        {
            if constexpr (std::is_void_v<decltype(std::forward<Fn>(body)())>)
            {
                std::forward<Fn>(body)();
                connection_->commit();
            }
            else
            {
                auto result = std::forward<Fn>(body)();
                connection_->commit();
                return result;
            }
        }
        catch (...)
        {
            try
            {
                connection_->rollback();
            }
            catch (...)
            {
                // 回滚失败不掩盖原始异常。
            }
            throw;
        }
    }

private:
    /** 参与写入的字段。 */
    [[nodiscard]] std::vector<const FieldInfo *> writableFields() const
    {
        std::vector<const FieldInfo *> result;
        for (const FieldInfo &info : meta_->fields())
        {
            if (info.isWritable()) result.push_back(&info);
        }
        return result;
    }

    /**
     * @brief 主键是否「未赋值」（交给数据库生成）。
     * @param id 主键单元格。
     * @return 未赋值返回 true。
     */
    [[nodiscard]] static bool isUnset(const Cell &id)
    {
        if (id.isNull()) return true;
        if (const auto number = id.asInt(); number.has_value()) return *number == 0;
        if (const auto text = id.asText(); text.has_value()) return text->empty();
        return false;
    }

    /**
     * @brief 执行 SELECT 并装配成模型列表。
     * @param statement 语句。
     * @return 结果列表。
     */
    [[nodiscard]] std::vector<Model> runSelect(const SqlStatement &statement) const
    {
        const std::unique_ptr<Statement> prepared = connection_->prepare(statement.sql());
        prepared->bindAll(statement.params());
        const std::unique_ptr<Cursor> cursor = prepared->query();

        std::vector<Model> items;
        std::vector<std::string> names; // 列名整行不变，第一行取一次
        while (cursor->next())
        {
            if (names.empty())
            {
                names.reserve(cursor->columnCount());
                for (std::size_t index = 0; index < cursor->columnCount(); ++index)
                {
                    names.push_back(cursor->columnName(index));
                }
            }
            Model model{};
            assemble(model, *cursor, names);
            items.push_back(std::move(model));
        }
        return items;
    }

    /**
     * @brief 把当前行灌进实例：先平铺列，再装配关联对象。
     *
     * 平铺列按列名查 `ModelMeta`；关联列的别名形如 `sclass.id`（见
     * `Query::with`），按前缀收集后写到关联成员的地址上。
     *
     * @param model 实例。
     * @param cursor 游标。
     * @param names 结果集的列名（与列序号同序）。
     */
    void assemble(Model &model, const Cursor &cursor, const std::vector<std::string> &names) const
    {
        for (std::size_t index = 0; index < names.size(); ++index)
        {
            const FieldInfo *info = meta_->field(names[index]);
            if (info == nullptr || info->write == nullptr) continue;
            info->write(&model, cursor.value(index));
        }

        for (const FieldInfo &info : meta_->fields())
        {
            if (info.type != FieldType::AssociationModel || info.related == nullptr ||
                info.memberAddress == nullptr)
            {
                continue;
            }

            const std::string prefix = info.name + ".";
            void *member = info.memberAddress(&model);

            for (const FieldInfo &relatedField : info.related->fields())
            {
                if (!relatedField.isColumn() || relatedField.write == nullptr) continue;

                // 只装配结果集里真的出现的关联列（没 with 的关联自然跳过）。
                bool found = false;
                for (const std::string &name : names)
                {
                    if (name == prefix + relatedField.column)
                    {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;

                relatedField.write(member, cursor.value(prefix + relatedField.column));
            }
        }
    }

    Connection *connection_ = nullptr;
    const ModelMeta *meta_ = nullptr;
};

/**
 * @brief 便捷函数：打开一个 SQLite 会话作用域的连接。
 * @param path 文件路径（`:memory:` 为内存库）。
 * @return 连接。
 * @throws DbError 打开失败。
 */
[[nodiscard]] inline std::unique_ptr<Connection> openSqlite(const std::string &path)
{
    return SqliteConnection::open(path);
}

} // namespace ipage

#endif // IPAGE_SESSION_HPP
