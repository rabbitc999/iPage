/**
 * @file main.cpp
 * @brief 用法示例：对应 Java 版 README 里的「学生 - 班级」例子。
 *
 * 编译运行：
 *
 * ```sh
 * make && ./build/example
 * ```
 *
 * @author 小兔糖糖OvO
 * @version 1.0
 * @since 1.0
 */

#include "ipage/schema.hpp"
#include "ipage/session.hpp"
#include "ipage/sql.hpp"

#include <cstdio>
#include <iostream>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
//  1. 声明模型（对应 Java 里给字段加 @ModelField 注解）
// ---------------------------------------------------------------------------

/** 班级。 */
struct Sclass
{
    int id = 0;         ///< 主键。
    std::string name;   ///< 班级名。
};

/** 学生。 */
struct Student
{
    int id = 0;          ///< 主键。
    std::string name;    ///< 姓名。
    int age = 0;         ///< 年龄。
    int sclassId = 0;    ///< 班级外键。
    Sclass sclass;       ///< 关联对象（查询时 JOIN 装配，不落库）。
};

// ---------------------------------------------------------------------------
//  2. 绑定字段表（对应 Java 的注解反射）
// ---------------------------------------------------------------------------

IPAGE_MODEL(Sclass,
            ipage::id("id", &Sclass::id),
            ipage::text("name", &Sclass::name));

IPAGE_MODEL(Student,
            ipage::id("id", &Student::id),
            ipage::text("name", &Student::name),
            ipage::number("age", &Student::age),
            ipage::foreignKey("sclassId", &Student::sclassId, "sclass_id"),
            ipage::association("sclass", &Student::sclass, ipage::metaOf<Sclass>(), "sclass_id"));

// ---------------------------------------------------------------------------
//  3. 用起来
// ---------------------------------------------------------------------------

int main()
{
    // 内存库；换 "demo.db" 就落到磁盘。
    auto connection = ipage::SqliteConnection::open(":memory:");
    std::cout << "连接：" << connection->describe() << "\n";

    // 按字段表自动建表（已存在则只补缺列）。
    ipage::SchemaSync schema(*connection);
    schema.sync<Sclass>();
    schema.sync<Student>();

    ipage::Session<Sclass> classes(*connection);
    ipage::Session<Student> students(*connection);

    // 写入：insert 会把自增主键回填到对象上。
    Sclass oneClass;
    oneClass.name = "三年二班";
    classes.insert(oneClass);

    for (const auto &[name, age] : std::vector<std::pair<std::string, int>>{
             {"张三", 20}, {"李四", 17}, {"王五", 25}, {"赵六", 16}})
    {
        Student student;
        student.name = name;
        student.age = age;
        student.sclassId = oneClass.id;
        students.insert(student);
    }
    std::cout << "已写入 " << students.count() << " 名学生\n\n";

    // 查询：条件用运算符表达式写，值一律走占位符绑定。
    ipage::Query adults = students.query();
    adults.where(ipage::field("age") >= 18).orderBy("age", ipage::SortType::Desc);
    std::cout << "成年人（倒序）：\n";
    for (const Student &student : students.selectList(adults))
    {
        std::cout << "  " << student.name << "，" << student.age << " 岁\n";
    }

    // 关联装配：with 会自动 LEFT JOIN，并把关联表各列灌进嵌套成员。
    std::cout << "\n带班级信息的前两名：\n";
    ipage::Query withClass = students.query();
    withClass.with("sclass").orderBy("age").limit(2);
    for (const Student &student : students.selectList(withClass))
    {
        std::cout << "  " << student.name << " @ " << student.sclass.name << "\n";
    }

    // 分页：页号从 1 开始，返回总条数与总页数。
    ipage::Page<Student> page = students.selectPage(students.query(), 1, 3);
    std::printf("\n第 %lld 页 / 共 %lld 页，总计 %lld 条\n", page.page, page.pages(), page.total);

    // 更新：只写可写字段，主键用于定位。
    if (std::optional<Student> found = students.findById(ipage::Cell(2)); found.has_value())
    {
        found->age = 18;
        std::cout << "更新 " << found->name << " 的年龄：" << students.update(*found) << " 行\n";
    }

    // 事务：正常返回则提交，抛异常则回滚。
    try
    {
        students.transaction([&students, &oneClass]() {
            Student student;
            student.name = "钱七";
            student.age = 22;
            student.sclassId = oneClass.id;
            students.insert(student);
            throw std::runtime_error("模拟失败");
        });
    }
    catch (const std::runtime_error &error)
    {
        std::cout << "事务已回滚（" << error.what() << "），当前仍有 " << students.count()
                  << " 名学生\n";
    }

    // 删除
    std::cout << "删除 16 岁的学生：" << students.deleteWhere(
                     students.query().where(ipage::field("age") < 17))
              << " 行\n";
    std::cout << "剩余 " << students.count() << " 名\n";

    // 诊断：看一眼生成的 SQL 与参数。
    ipage::Query probe = students.query();
    probe.filter("age", ipage::OperatorType::Gte, {ipage::Cell(18)});
    std::cout << "\n生成的 SQL：\n  " << probe.build().toString() << "\n";
    return 0;
}
