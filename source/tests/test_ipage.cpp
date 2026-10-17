/**
 * @file test_ipage.cpp
 * @brief 端到端自测：建表 / 插入 / 查询 / 更新 / 删除 / 分页 / 事务。
 *
 * 不依赖任何测试框架，直接 `return 非 0` 表示失败，方便脚本调用：
 *
 * ```sh
 * g++ -std=c++17 -I. -I sqlite test_ipage.cpp ipage 下的 .cpp 与 sqlite3.c
 * ```
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#include "ipage/schema.hpp"
#include "ipage/session.hpp"
#include "ipage/sql.hpp"

#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

namespace
{

int g_failures = 0;

/** 断言。 */
void expect(bool condition, const std::string &what)
{
    if (condition)
    {
        std::cout << "  ok   " << what << "\n";
    }
    else
    {
        std::cout << "  FAIL " << what << "\n";
        ++g_failures;
    }
}

/** 断言相等（可打印）。 */
template <class A, class B>
void expectEq(const A &actual, const B &expected, const std::string &what)
{
    const bool same = (actual == expected);
    if (same)
    {
        std::cout << "  ok   " << what << "\n";
    }
    else
    {
        std::cout << "  FAIL " << what << "（得到 " << actual << "，期望 " << expected << "）\n";
        ++g_failures;
    }
}

} // namespace

// ---------------------------------------------------------------------------
//  模型
// ---------------------------------------------------------------------------

/** 班级。 */
struct Sclass
{
    int id = 0;
    std::string name;
};

/** 学生。 */
struct Student
{
    int id = 0;
    std::string name;
    int age = 0;
    int sclassId = 0;
    bool active = true;
};

IPAGE_MODEL(Sclass,
            ipage::id("id", &Sclass::id),
            ipage::text("name", &Sclass::name));

IPAGE_MODEL(Student,
            ipage::id("id", &Student::id),
            ipage::text("name", &Student::name),
            ipage::number("age", &Student::age),
            ipage::foreignKey("sclassId", &Student::sclassId, "sclass_id"),
            ipage::flag("active", &Student::active));

int main()
{
    std::cout << "== 建表 ==\n";
    auto connection = ipage::SqliteConnection::open(":memory:");
    ipage::SchemaSync sync(*connection);

    const std::string ddl = sync.createTableSql<Student>();
    std::cout << "  DDL: " << ddl << "\n";
    expect(ddl.find("AUTOINCREMENT") != std::string::npos, "主键带 AUTOINCREMENT");

    expectEq(sync.sync<Student>().size(), std::size_t{1}, "首次 sync 建表");
    expectEq(sync.sync<Student>().size(), std::size_t{0}, "重复 sync 不动作");
    expectEq(sync.sync<Sclass>().size(), std::size_t{1}, "Sclass 建表");

    ipage::Session<Student> students(*connection);
    ipage::Session<Sclass> classes(*connection);

    std::cout << "== 插入 ==\n";
    {
        Sclass c;
        c.name = "一班";
        const std::int64_t classId = classes.insert(c);
        expectEq(classId, c.id, "班级主键回填一致");

        Student a;
        a.name = "张三";
        a.age = 20;
        a.sclassId = static_cast<int>(classId);
        Student b;
        b.name = "李四";
        b.age = 17;
        b.sclassId = static_cast<int>(classId);
        Student c2;
        c2.name = "王五";
        c2.age = 25;
        c2.sclassId = static_cast<int>(classId);
        c2.active = false;
        students.insert(a);
        students.insert(b);
        students.insert(c2);
        expect(a.id > 0 && b.id > 0 && c2.id > 0, "学生主键回填");
    }
    expectEq(static_cast<long long>(students.count()), 3LL, "总数 3");

    std::cout << "== 查询 ==\n";
    {
        ipage::Query q = students.query();
        std::cout << "  SQL: " << q.build().sql() << "\n";
    }
    {
        auto all = students.selectAll();
        expectEq(all.size(), std::size_t{3}, "selectAll 得到 3 条");
        expectEq(all.front().name, std::string("张三"), "第一条是张三");
    }
    {
        auto one = students.findById(ipage::Cell(1));
        expect(one.has_value(), "findById(1) 命中");
        if (one.has_value())
        {
            expectEq(one->name, std::string("张三"), "findById 名字正确");
            expectEq(one->age, 20, "findById 年龄正确");
            expectEq(one->active, true, "findById 布尔字段正确");
        }
    }
    {
        ipage::Query q = students.query();
        q.where(ipage::field("age") < 18);
        auto minors = students.selectList(q);
        expectEq(minors.size(), std::size_t{1}, "年龄 < 18 只有 1 人");
        expectEq(minors.empty() ? std::string() : minors.front().name, std::string("李四"),
                 "未成年是李四");
    }
    {
        ipage::Query q = students.query();
        q.where(ipage::field("name").contains("张") || ipage::field("age") >= 25);
        auto hit = students.selectList(q);
        expectEq(hit.size(), std::size_t{2}, "模糊 + 或条件得到 2 条");
    }
    {
        auto one = students.selectOne(students.query().orderBy("age", ipage::SortType::Desc));
        expect(one.has_value() && one->name == "王五", "按年龄倒序第一条是王五");
    }
    {
        ipage::Query q = students.query();
        q.filter("active", ipage::OperatorType::Eq, {ipage::Cell(true)});
        expectEq(students.count(q), 2LL, "在职 2 人");
    }
    {
        expect(students.exists(students.query().where(ipage::field("age") == 17)),
               "存在 17 岁的学生");
        expect(!students.exists(students.query().where(ipage::field("age") == 99)),
               "不存在 99 岁的学生");
    }

    std::cout << "== 分页 ==\n";
    {
        ipage::Query q = students.query();
        q.orderBy("age");
        auto page = students.selectPage(q, 2, 2);
        expectEq(page.total, 3LL, "分页总数 3");
        expectEq(page.pages(), 2LL, "共 2 页");
        expectEq(page.items.size(), std::size_t{1}, "第 2 页有 1 条");
        expect(page.hasPrevious() && !page.hasNext(), "第 2 页没有下一页");
        if (!page.items.empty())
        {
            expectEq(page.items.front().name, std::string("王五"), "第 2 页是王五");
        }
    }

    std::cout << "== 更新 ==\n";
    {
        auto one = students.findById(ipage::Cell(2));
        expect(one.has_value(), "更新前能查到 2 号");
        if (one.has_value())
        {
            one->age = 19;
            one->name = "李四四";
            expectEq(students.update(*one), std::size_t{1}, "更新 1 行");
            auto again = students.findById(ipage::Cell(2));
            expect(again.has_value() && again->age == 19, "年龄已改成 19");
            expect(again.has_value() && again->name == "李四四", "名字已改成李四四");
        }
    }

    std::cout << "== 事务 ==\n";
    {
        const long long before = students.count();
        try
        {
            students.transaction([&students, before]() {
                Student ghost;
                ghost.name = "幽灵";
                ghost.age = 30;
                ghost.sclassId = 1;
                students.insert(ghost);
                expectEq(static_cast<long long>(students.count()), before + 1, "事务内可见新行");
                throw std::runtime_error("故意失败");
            });
            expect(false, "事务应当抛出异常");
        }
        catch (const std::runtime_error &)
        {
            expect(true, "异常按预期外抛");
        }
        expectEq(static_cast<long long>(students.count()), before, "回滚后行数复原");
    }
    {
        const long long before = students.count();
        const std::int64_t id = students.transaction([&students]() {
            Student extra;
            extra.name = "赵六";
            extra.age = 22;
            extra.sclassId = 1;
            return students.insert(extra);
        });
        expect(id > 0, "事务提交返回主键");
        expectEq(static_cast<long long>(students.count()), before + 1, "提交后行数 +1");
    }

    std::cout << "== 删除 ==\n";
    {
        expectEq(students.deleteById(ipage::Cell(1)), std::size_t{1}, "按主键删 1 行");
        expect(!students.findById(ipage::Cell(1)).has_value(), "1 号已删除");
    }
    {
        ipage::Query q = students.query();
        q.where(ipage::field("age") >= 20);
        const std::size_t removed = students.deleteWhere(q);
        expect(removed >= 1, "按条件删除生效");
    }

    std::cout << "== 异常 ==\n";
    {
        bool threw = false;
        try
        {
            ipage::Query q = students.query();
            q.filter("不存在的列", ipage::OperatorType::Eq, {ipage::Cell(1)});
        }
        catch (const std::invalid_argument &)
        {
            threw = true;
        }
        expect(threw, "未知列被拒绝（列白名单）");
    }
    {
        bool threw = false;
        try
        {
            connection->execute("SELECT * FROM 没有这张表");
        }
        catch (const ipage::DbError &error)
        {
            threw = true;
            std::cout << "  捕获: " << error.what() << "\n";
        }
        expect(threw, "SQL 错误抛 DbError");
    }

    std::cout << "\n";
    if (g_failures == 0)
    {
        std::cout << "全部通过\n";
        return EXIT_SUCCESS;
    }
    std::cout << g_failures << " 项失败\n";
    return EXIT_FAILURE;
}
