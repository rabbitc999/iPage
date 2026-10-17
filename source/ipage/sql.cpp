/**
 * @file sql.cpp
 * @brief `Condition` / `Query` / `SqlStatement` 的实现。
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#include "sql.hpp"

#include "model.hpp"

#include <algorithm>
#include <stdexcept>

namespace ipage
{

namespace
{

/** 主表别名（与 Java 版一致）。 */
constexpr std::string_view kMainAlias = "t_";

/**
 * @brief 转义 `LIKE` 里的通配符，并补上 `%`。
 * @param value 原始文本。
 * @param mode 0 = 包含，1 = 前缀，2 = 后缀。
 * @return 模式串。
 */
std::string likePattern(std::string_view value, int mode)
{
    std::string escaped;
    escaped.reserve(value.size() + 4);
    for (const char ch : value)
    {
        if (ch == '%' || ch == '_' || ch == '\\')
        {
            escaped.push_back('\\');
        }
        escaped.push_back(ch);
    }
    if (mode == 1)
    {
        return escaped + "%";
    }
    if (mode == 2)
    {
        return "%" + escaped;
    }
    return "%" + escaped + "%";
}

/**
 * @brief 列名解析：只允许模型里真实存在的列，避免外部输入进 SQL。
 * @param meta 模型元数据。
 * @param nameOrColumn 字段名或列名。
 * @return 列名。
 * @throws std::invalid_argument 列不存在。
 */
const std::string &resolveColumn(const ModelMeta &meta, std::string_view nameOrColumn)
{
    const FieldInfo *info = meta.field(nameOrColumn);
    if (info == nullptr)
    {
        throw std::invalid_argument("模型 " + meta.name() + " 没有字段 " + std::string(nameOrColumn));
    }
    return info->column;
}

} // namespace

// ---------------------------------------------------------------------------
//  Condition
// ---------------------------------------------------------------------------

Condition Condition::compare(std::string column, OperatorType op, std::vector<Cell> values,
                             bool rawColumn)
{
    Condition node(Kind::Field);
    node.term_.column = std::move(column);
    node.term_.op = op;
    node.term_.values = std::move(values);
    node.term_.rawColumn = rawColumn;
    return node;
}

Condition Condition::alwaysTrue()
{
    Condition node(Kind::Value);
    node.constant_ = true;
    return node;
}

Condition Condition::alwaysFalse()
{
    Condition node(Kind::Value);
    node.constant_ = false;
    return node;
}

Condition Condition::conjunction(std::vector<Condition> parts)
{
    Condition node(Kind::And);
    node.children_ = std::move(parts);
    return node;
}

Condition Condition::disjunction(std::vector<Condition> parts)
{
    Condition node(Kind::Or);
    node.children_ = std::move(parts);
    return node;
}

Condition Condition::negation(Condition part)
{
    Condition node(Kind::Not);
    node.children_.push_back(std::move(part));
    return node;
}

Condition Condition::operator&&(Condition other) const
{
    if (isTrivial()) return other;
    if (other.isTrivial()) return *this;

    std::vector<Condition> parts;
    if (kind_ == Kind::And)
    {
        parts = children_;
    }
    else
    {
        parts.push_back(*this);
    }
    if (other.kind_ == Kind::And)
    {
        for (const Condition &part : other.children_) parts.push_back(part);
    }
    else
    {
        parts.push_back(std::move(other));
    }
    return conjunction(std::move(parts));
}

Condition Condition::operator||(Condition other) const
{
    if (kind_ == Kind::Value && constant_) return *this;      // 恒真 ∨ x = 恒真
    if (other.kind_ == Kind::Value && other.constant_) return other;
    if (kind_ == Kind::Value && !constant_) return other;     // 恒假 ∨ x = x
    if (other.kind_ == Kind::Value && !other.constant_) return *this;

    std::vector<Condition> parts;
    if (kind_ == Kind::Or)
    {
        parts = children_;
    }
    else
    {
        parts.push_back(*this);
    }
    if (other.kind_ == Kind::Or)
    {
        for (const Condition &part : other.children_) parts.push_back(part);
    }
    else
    {
        parts.push_back(std::move(other));
    }
    return disjunction(std::move(parts));
}

void Condition::render(std::string &out, std::vector<Cell> &params, const Dialect &dialect,
                       std::string_view tableAlias) const
{
    switch (kind_)
    {
    case Kind::Value:
        out += constant_ ? "1 = 1" : "1 = 0";
        return;

    case Kind::Field:
    {
        const std::string column =
            term_.rawColumn ? term_.column : dialect.qualify(tableAlias, term_.column);
        const OperatorType op = term_.op;

        if (op == OperatorType::IsNull || op == OperatorType::IsNotNull)
        {
            out += column;
            out += ' ';
            out += symbol(op);
            return;
        }

        out += column;
        out += ' ';
        out += symbol(op);
        out += ' ';

        if (needsCollection(op))
        {
            if (term_.values.empty())
            {
                // `IN ()` 在多数数据库里是语法错误，退化成恒假。
                out += "1 = 0";
                return;
            }
            out += '(';
            for (std::size_t index = 0; index < term_.values.size(); ++index)
            {
                if (index > 0) out += ", ";
                out += dialect.placeholder(params.size());
                params.push_back(term_.values[index]);
            }
            out += ')';
            return;
        }

        out += dialect.placeholder(params.size());
        if (!term_.values.empty())
        {
            params.push_back(term_.values.front());
        }
        else
        {
            params.emplace_back();
        }
        return;
    }

    case Kind::And:
    case Kind::Or:
    {
        if (children_.empty())
        {
            out += (kind_ == Kind::And) ? "1 = 1" : "1 = 0";
            return;
        }
        out += '(';
        for (std::size_t index = 0; index < children_.size(); ++index)
        {
            if (index > 0)
            {
                out += (kind_ == Kind::And) ? " AND " : " OR ";
            }
            children_[index].render(out, params, dialect, tableAlias);
        }
        out += ')';
        return;
    }

    case Kind::Not:
        out += "NOT (";
        children_.front().render(out, params, dialect, tableAlias);
        out += ')';
        return;
    }
}

// ---------------------------------------------------------------------------
//  FieldRef
// ---------------------------------------------------------------------------

Condition FieldRef::operator==(Cell value) const
{
    return Condition::compare(column_, OperatorType::Eq, {std::move(value)});
}

Condition FieldRef::operator!=(Cell value) const
{
    return Condition::compare(column_, OperatorType::Ne, {std::move(value)});
}

Condition FieldRef::operator>(Cell value) const
{
    return Condition::compare(column_, OperatorType::Gt, {std::move(value)});
}

Condition FieldRef::operator<(Cell value) const
{
    return Condition::compare(column_, OperatorType::Lt, {std::move(value)});
}

Condition FieldRef::operator>=(Cell value) const
{
    return Condition::compare(column_, OperatorType::Gte, {std::move(value)});
}

Condition FieldRef::operator<=(Cell value) const
{
    return Condition::compare(column_, OperatorType::Lte, {std::move(value)});
}

Condition FieldRef::isNull() const
{
    return Condition::compare(column_, OperatorType::IsNull, {});
}

Condition FieldRef::isNotNull() const
{
    return Condition::compare(column_, OperatorType::IsNotNull, {});
}

Condition FieldRef::contains(std::string_view value) const
{
    return Condition::compare(column_, OperatorType::Like, {Cell(likePattern(value, 0))});
}

Condition FieldRef::startsWith(std::string_view value) const
{
    return Condition::compare(column_, OperatorType::StartsWith, {Cell(likePattern(value, 1))});
}

Condition FieldRef::endsWith(std::string_view value) const
{
    return Condition::compare(column_, OperatorType::EndsWith, {Cell(likePattern(value, 2))});
}

Condition FieldRef::in(std::vector<Cell> values) const
{
    return Condition::compare(column_, OperatorType::In, std::move(values));
}

Condition FieldRef::notIn(std::vector<Cell> values) const
{
    return Condition::compare(column_, OperatorType::NotIn, std::move(values));
}

FieldRef field(std::string column)
{
    return FieldRef(std::move(column));
}

Condition alwaysTrue()
{
    return Condition::alwaysTrue();
}

Condition alwaysFalse()
{
    return Condition::alwaysFalse();
}

Condition all(std::vector<Condition> parts)
{
    return Condition::conjunction(std::move(parts));
}

Condition any(std::vector<Condition> parts)
{
    return Condition::disjunction(std::move(parts));
}

Condition none(Condition part)
{
    return Condition::negation(std::move(part));
}

// ---------------------------------------------------------------------------
//  Query
// ---------------------------------------------------------------------------

Query::Query(const ModelMeta &meta, const Dialect &dialect) : meta_(&meta), dialect_(&dialect) {}

Query::Query(const Query &other)
    : meta_(other.meta_),
      dialect_(other.dialect_),
      orders_(other.orders_),
      selections_(other.selections_),
      requestedColumns_(other.requestedColumns_),
      joins_(other.joins_),
      explicitSelection_(other.explicitSelection_),
      counting_(other.counting_),
      limit_(other.limit_),
      offset_(other.offset_)
{
    if (other.condition_ != nullptr)
    {
        condition_ = std::make_unique<Condition>(*other.condition_);
    }
}

Query &Query::operator=(const Query &other)
{
    if (this == &other) return *this;
    meta_ = other.meta_;
    dialect_ = other.dialect_;
    orders_ = other.orders_;
    selections_ = other.selections_;
    requestedColumns_ = other.requestedColumns_;
    joins_ = other.joins_;
    explicitSelection_ = other.explicitSelection_;
    counting_ = other.counting_;
    limit_ = other.limit_;
    offset_ = other.offset_;
    condition_ = other.condition_ != nullptr ? std::make_unique<Condition>(*other.condition_)
                                             : nullptr;
    return *this;
}

Query &Query::filter(std::string column, OperatorType op, std::vector<Cell> values)
{
    const std::string &resolved = resolveColumn(*meta_, column);
    return where(Condition::compare(resolved, op, std::move(values)));
}

Query &Query::where(Condition condition)
{
    if (condition_ != nullptr)
    {
        condition_ = std::make_unique<Condition>(*condition_ && std::move(condition));
    }
    else
    {
        condition_ = std::make_unique<Condition>(std::move(condition));
    }
    return *this;
}

Query &Query::orderBy(std::string column, SortType direction)
{
    Order order;
    order.column = resolveColumn(*meta_, column);
    order.direction = direction;
    order.tableAlias = std::string(kMainAlias);
    orders_.push_back(std::move(order));
    return *this;
}

Query &Query::limit(long long value)
{
    limit_ = value;
    return *this;
}

Query &Query::offset(long long value)
{
    offset_ = value;
    return *this;
}

Query &Query::select(std::vector<std::string> columns)
{
    requestedColumns_ = std::move(columns);
    explicitSelection_ = true;
    return *this;
}

Query &Query::aggregate(std::string aliasName, std::string column, SqlFunction function)
{
    explicitSelection_ = true;
    SelectedField selected;
    selected.aliasName = std::move(aliasName);
    selected.function = function;
    selected.isAggregate = true;
    selected.tableAlias = std::string(kMainAlias);
    if (!column.empty())
    {
        selected.column = resolveColumn(*meta_, column);
    }
    selections_.push_back(std::move(selected));
    return *this;
}

Query &Query::countOnly()
{
    counting_ = true;
    explicitSelection_ = true;
    selections_.clear();
    requestedColumns_.clear();
    orders_.clear();
    return *this;
}

Query &Query::join(const ModelMeta &related, std::string foreignKey, std::string relatedForeignKey)
{
    const std::string &local = resolveColumn(*meta_, foreignKey);
    if (related.field(relatedForeignKey) == nullptr)
    {
        throw std::invalid_argument("模型 " + related.name() + " 没有字段 " + relatedForeignKey);
    }

    const std::string alias = "t_" + std::to_string(joins_.size() + 2);
    JoinSpec spec;
    spec.meta = &related;
    spec.alias = alias;
    spec.foreignKey = local;
    spec.relatedForeignKey = related.field(relatedForeignKey)->column;
    joins_.push_back(std::move(spec));
    return *this;
}

Query &Query::with(const std::string &fieldName)
{
    const FieldInfo *info = meta_->field(fieldName);
    if (info == nullptr)
    {
        throw std::invalid_argument("模型 " + meta_->name() + " 没有字段 " + fieldName);
    }
    if (info->type != FieldType::AssociationModel || info->related == nullptr)
    {
        throw std::invalid_argument("字段 " + fieldName + " 不是关联字段（用 ipage::association 声明）");
    }

    // JOIN 条件：主表上的外键列（声明 association 时给的 column）→ 关联表主键。
    const std::string &foreignKey = resolveColumn(*meta_, info->column);
    const std::string alias = "t_" + std::to_string(joins_.size() + 2);
    JoinSpec spec;
    spec.meta = info->related;
    spec.alias = alias;
    spec.foreignKey = foreignKey;
    spec.relatedForeignKey = info->related->idColumn();
    joins_.push_back(std::move(spec));

    // 选中关联表的全部列，别名 `字段名.列名`。
    for (const FieldInfo &relatedField : info->related->fields())
    {
        if (!relatedField.isColumn()) continue;
        SelectedField selected;
        selected.aliasName = fieldName + "." + relatedField.column;
        selected.column = relatedField.column;
        selected.tableAlias = alias;
        selected.isJoin = true;
        selections_.push_back(std::move(selected));
    }
    return *this;
}

void Query::ensureDefaultSelections()
{
    // 显式给过选列（select / aggregate / countOnly）就不再补默认；
    // with() 只加关联列，主表列仍走默认。
    if (explicitSelection_) return;

    std::vector<std::string> wanted = requestedColumns_;
    if (wanted.empty())
    {
        for (const FieldInfo &info : meta_->fields())
        {
            if (info.isColumn()) wanted.push_back(info.column);
        }
    }

    for (const std::string &name : wanted)
    {
        const std::string &column = resolveColumn(*meta_, name);
        SelectedField selected;
        selected.aliasName = column;
        selected.column = column;
        selected.tableAlias = std::string(kMainAlias);
        selections_.push_back(std::move(selected));
    }
}

SqlStatement Query::build() const
{
    Query copy(*this); // 拷贝构造已覆盖全部成员（含条件树深拷贝）
    copy.ensureDefaultSelections();

    const Dialect &dialect = *dialect_;
    std::string sql;
    std::vector<Cell> params;
    sql.reserve(256);

    // SELECT
    sql += "SELECT ";
    if (copy.counting_)
    {
        sql += "COUNT(*)";
    }
    else
    {
        for (std::size_t index = 0; index < copy.selections_.size(); ++index)
        {
            const SelectedField &selected = copy.selections_[index];
            if (index > 0) sql += ", ";
            if (selected.function.has_value())
            {
                sql += sqlName(*selected.function);
                sql += '(';
                if (selected.column.empty())
                {
                    sql += '*';
                }
                else
                {
                    sql += dialect.qualify(selected.tableAlias, selected.column);
                }
                sql += ')';
            }
            else
            {
                sql += dialect.qualify(selected.tableAlias, selected.column);
            }
            sql += " AS ";
            sql += dialect.quote(selected.aliasName);
        }
    }

    // FROM
    sql += " FROM ";
    sql += dialect.quote(meta_->table());
    sql += " AS ";
    sql += dialect.quote(kMainAlias);

    // LEFT JOIN 关联表
    for (const JoinSpec &spec : copy.joins_)
    {
        sql += " LEFT JOIN ";
        sql += dialect.quote(spec.meta->table());
        sql += " AS ";
        sql += dialect.quote(spec.alias);
        sql += " ON ";
        sql += dialect.qualify(spec.alias, spec.relatedForeignKey);
        sql += " = ";
        sql += dialect.qualify(kMainAlias, spec.foreignKey);
    }

    // WHERE
    if (copy.condition_ != nullptr && !copy.condition_->isTrivial())
    {
        sql += " WHERE ";
        copy.condition_->render(sql, params, dialect, kMainAlias);
    }

    // GROUP BY：聚合查询必须覆盖所有非聚合列。
    if (!copy.counting_)
    {
        const bool hasAggregate =
            std::any_of(copy.selections_.begin(), copy.selections_.end(),
                        [](const SelectedField &item) { return item.function.has_value(); });
        if (hasAggregate)
        {
            std::string group;
            for (const SelectedField &selected : copy.selections_)
            {
                if (selected.function.has_value()) continue;
                if (group.empty())
                {
                    group = dialect.qualify(selected.tableAlias, selected.column);
                }
                else
                {
                    group += ", ";
                    group += dialect.qualify(selected.tableAlias, selected.column);
                }
            }
            if (!group.empty())
            {
                sql += " GROUP BY ";
                sql += group;
            }
        }
    }

    // ORDER BY
    if (!copy.orders_.empty())
    {
        sql += " ORDER BY ";
        for (std::size_t index = 0; index < copy.orders_.size(); ++index)
        {
            if (index > 0) sql += ", ";
            sql += dialect.qualify(copy.orders_[index].tableAlias, copy.orders_[index].column);
            sql += ' ';
            sql += keyword(copy.orders_[index].direction);
        }
    }

    // LIMIT / OFFSET
    sql += dialect.limitClause(copy.limit_, copy.offset_);

    return SqlStatement(std::move(sql), std::move(params));
}

// ---------------------------------------------------------------------------
//  SqlStatement
// ---------------------------------------------------------------------------

std::string SqlStatement::toString() const
{
    std::string out = sql_;
    out += "  -- [";
    for (std::size_t index = 0; index < params_.size(); ++index)
    {
        if (index > 0) out += ", ";
        if (params_[index].isNull())
        {
            out += "NULL";
        }
        else if (params_[index].isText())
        {
            out += '\'';
            out += params_[index].toText();
            out += '\'';
        }
        else
        {
            out += params_[index].toText();
        }
    }
    out += ']';
    return out;
}

} // namespace ipage
