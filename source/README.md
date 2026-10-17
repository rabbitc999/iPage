# ipage for C++

Java 框架 [ipage](../../README.md) 的 C++17 移植。同一套思路、同一套语义，换成编译期就能查错的写法。

零外部依赖：只用到 C++ 标准库 + 内嵌的 SQLite amalgamation（`sqlite/sqlite3.c`）。

---

## 它解决什么问题

写数据库代码时，最烦的两件事：

```cpp
// 手写 SQL：列名打错要到运行期才发现，参数顺序一错就静默出错
connection.execute("UPDATE student SET age = " + std::to_string(age) + " WHERE id = " + id);

// 手写映射：加一个字段要改三处（SQL、取值、赋值）
while (cursor.next()) {
    Student s;
    s.id   = cursor.getInt("id");
    s.name = cursor.getString("name");
    // 又漏了一个字段……
}
```

ipage 的做法：**把模型的字段表声明一次**，之后建表、增删改查、结果映射全部由它推导。

```cpp
struct Student
{
    int         id = 0;
    std::string name;
    int         age = 0;
};

IPAGE_MODEL(Student,
    ipage::id("id", &Student::id),
    ipage::text("name", &Student::name),
    ipage::number("age", &Student::age));

ipage::Session<Student> students(*connection);

Student s;
s.name = "张三";
s.age  = 20;
students.insert(s);                        // s.id 自动回填

auto all    = students.selectAll();        // std::vector<Student>
auto found  = students.findById(ipage::Cell(1));
auto adults = students.selectList(
    students.query().where(ipage::field("age") >= 18));
```

加字段只改模型和字段表两处，SQL 一句都不用碰。

---

## 快速开始

```sh
cd source
make                      # 构建并跑测试（Windows 上 mingw32-make）
./build/example           # 跑示例
```

或者用 CMake：

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
ctest --test-dir build --output-on-failure
```

把 `ipage/` 加进头文件搜索路径，链接 `ipage/*.cpp` + `sqlite/sqlite3.c` 即可，没有别的依赖。

---

## 与 Java 版的对应关系

| Java | C++ | 说明 |
| --- | --- | --- |
| `@ModelField` 注解 | 成员指针表 + `IPAGE_MODEL` | C++ 没有注解/反射，用成员指针做同样的事 |
| `FieldDescriptor` / `ModelMeta` | `Field<T,M>` → `FieldInfo` / `ModelMeta` | 声明期类型精确，运行期类型擦除 |
| `FieldType` 枚举 | `ipage::FieldType` | 九个值一一对应 |
| `OperatorType` | `ipage::OperatorType` | 十三个操作符，封闭枚举 |
| `Session` / `ModelSession` | `ipage::Session<Model>` | API 名字保持接近 |
| 关联装配（`joinAssociations`） | `Query::with(字段名)` | 自动 LEFT JOIN + 嵌套成员回填 |
| `SqlCondition` | `ipage::Condition` / `FieldRef` | 支持 `&&` `\|\|` 运算符 |
| `Query` | `ipage::Query` | 链式配置，`build()` 出版本 |
| `SqlStatement` | `ipage::SqlStatement` | SQL + 参数一起产出 |
| `dialect.Dialect` | `ipage::Dialect` | 换数据库只换方言 |
| `jdbc.*` | `ipage::Connection` / `Statement` / `Cursor` | 连接抽象，SQLite 是默认实现 |
| `SchemaSync` | `ipage::SchemaSync` | 建表 + 补列 |

### 刻意不一样的地方

**1. 关联字段传的是元数据，不是 `Class`。**

Java 里写 `@ModelField(byModel = Sclass.class)`；C++ 在声明期拿不到另一个类的元数据，改成传 `Sclass::meta()`：

```cpp
IPAGE_MODEL(Student,
    ipage::association("sclass", &Student::sclass, Sclass::meta(), "sclass_id"));

// 查询时一行装配：自动 LEFT JOIN，把关联表各列灌进 student.sclass
students.query().with("sclass");
```

**2. 列的 C++ 类型由成员指针推导。**

Java 要写 `@ModelField(type = FieldType.STRING)`，还要保证和字段类型一致。C++ 里 `&Student::name` 本身就带类型，`writeCell` 会在编译期校验能不能写回去，写错直接编译失败。

**3. 条件可以用运算符写。**

```cpp
students.query().where(ipage::field("age") >= 18 && ipage::field("name").contains("张"));
```

Java 版只能 `and_(gt(...), like(...))` 这样套。

---

## 模块

| 文件 | 职责 |
| --- | --- |
| `ipage/field.hpp` | 枚举（`FieldType` / `OperatorType` / …）+ `Cell` 单元格值 |
| `ipage/model.hpp` | `Field<T,M>` / `FieldInfo` / `ModelMeta` / `IPAGE_MODEL` |
| `ipage/dialect.hpp` | `Dialect` 接口 + `SqliteDialect` / `DefaultDialect` |
| `ipage/sql.hpp` | `Condition` / `Query` / `SqlStatement` |
| `ipage/connection.hpp` | `Connection` / `Statement` / `Cursor` 抽象 + SQLite 实现 |
| `ipage/session.hpp` | `Session<Model>`：查询、写入、分页、事务 |
| `ipage/schema.hpp` | `SchemaSync`：自动建表 / 补列 |
| `sqlite/` | SQLite 3.49.1 amalgamation |

---

## 安全性

- **参数一律绑定**。`Cell` 是唯一的值载体，没有一条路径把外部值拼进 SQL。
- **列名白名单**。`filter("xxx")` 的列名必须存在于 `ModelMeta`，否则抛 `std::invalid_argument`。
- **操作符封闭枚举**。SQL 片段从固定表取，不接受字符串。
- **`LIKE` 转义**。`contains("%")` 里的 `%` 会被转义成 `\%`，不会变成通配符。

---

## 测试

`tests/test_ipage.cpp` 覆盖建表、插入回填、查询、模糊匹配、关联装配、分页、更新、事务提交/回滚、批量与条件删除、列白名单、SQL 报错。43 项断言，全部通过。

```sh
make test
```

---

## 已知限制

- `Session` 不是线程安全的。多线程请每个线程各开一个 `Connection`。
- 只实现了 SQLite 方言；`Dialect` 接口已留好，MySQL / PostgreSQL 增加派生类即可。
- 不支持复合主键、不支持关联对象的自动级联保存（与 Java 版当前能力一致）。
- `SchemaSync` 不删列、不改类型——线上库上这些操作应当由人来做。

---

## 作者

小兔糖糖OvO
