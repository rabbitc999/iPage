# ipage

注解驱动的 **SQL 引擎**：用一个 Java 类描述一张表，框架负责生成 SQL、适配方言、按需建表。

不依赖 MyBatis，不依赖 Spring，不依赖任何 Web 框架。运行时只有 `slf4j-api` 一个依赖，
数据库驱动由使用方按需引入。

```java
ModelSession sql = ModelSession.sql("jdbc:postgresql://127.0.0.1:5432/school", "postgres", "123456");

sql.synchronizeSchema(Student.class);      // 表不存在就建，缺列就补

Student student = new Student();
student.name = "张三";
sql.insert(student);                       // 自增主键自动回填到 student.id

List<Student> rows = sql.selectList(
        Query.of(Student.class, sql.dialect()).like("name", "张"));
```

---

## 它解决什么

传统 JDBC 里，「类 → 表 → SQL」这条链上的每个环节都要手写，而且容易写错：
列名散落在字符串里、参数与占位符个数对不上、不同数据库的分页语法各写一遍、
上线前还要人肉核对建表语句。

ipage 把这条链收进框架：

| 环节 | 做法 |
| --- | --- |
| 类 → 表结构 | `@ModelClass` / `@ModelField` 声明列名、类型、关联，反射解析一次并缓存 |
| 查询 → SQL | `Query` 描述条件、排序、分页，`SqlStatement` 渲染成带 `?` 的 SQL + 参数列表 |
| SQL → 数据库 | `Dialect` 收口引号规则、分页语法、自增列声明、类型映射 |
| 结果 → 对象 | 选择列表里就登记好了「别名 → 字段路径」，回填不做字符串推断 |
| 建表 | `synchronizeSchema` 按模型建表、补列，只做加法 |

## 快速上手

### 1. 引入

```xml
<dependency>
    <groupId>rabbitc.ipage</groupId>
    <artifactId>ipage</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 驱动自己选，框架不替你决定 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.4</version>
</dependency>
```

### 2. 描述模型

```java
@ModelClass(name = "学员", table = "student", text = "${name}")
public class Student {

    @ModelField(column = "student_id", type = FieldType.MODEL_ID)
    Long studentId;

    @ModelField(column = "student_name", type = FieldType.STRING, text = "姓名")
    @ValidatorNotNull(message = "姓名不能为空")
    String name;

    @ModelField(column = "academy_id", type = FieldType.ASSOCIATION_ID, byModel = Academy.class)
    Long academyId;

    @ModelField(column = "academy_id", type = FieldType.ASSOCIATION_MODEL, byModel = Academy.class)
    Academy academy;                       // 查询时 LEFT JOIN 填充

    @ModelField(column = "score", type = FieldType.NUMBER, text = "绩点")
    BigDecimal score;
}
```

`FieldType` 决定这一列怎么参与 SQL：

| 类型 | 落库 | 查询时 |
| --- | --- | --- |
| `MODEL_ID` | 是（主键） | 可排序、可过滤 |
| `ASSOCIATION_ID` | 是（外键） | 可与关联表连表 |
| `ASSOCIATION_MODEL` | 否 | 由 LEFT JOIN 填充成对象 |
| `AGGREGATION` | 否 | 以 `function` 聚合子表，如「学院人数」 |
| `STRING` / `NUMBER` / `ENUM` / `DATE` / `BOOLEAN` | 是 | 可排序、可过滤 |

### 3. 建会话

```java
// 方言自动探测（推荐）
ModelSession sql = ModelSession.sql(provider);

// 或者自己给
ModelSession sql = new ModelSession(provider, Dialect.DEFAULT);

// 带表名前缀，多套环境共库
ModelSession sql = ModelSession.sql(provider, "dev_");
```

会话本身不持有连接，可以放心地做成单例共享；连接每次按需借还。

### 4. 增删改查

```java
// 查
Query query = Query.of(Student.class, sql.dialect())
        .like("name", "张")
        .between("score", 2, 5)
        .orderBy("studentId", SortType.DESC);

List<Student> all   = sql.selectList(query);
Page<Student> page  = sql.selectPage(query, 1, 20);
Optional<Student> one = sql.findById(Student.class, 1L);
long total          = sql.count(query);

// 写
sql.insert(student);          // 校验 → INSERT → 回填主键
sql.update(student);          // 校验（带 before 快照）→ UPDATE
sql.deleteById(Student.class, 1L);
sql.deleteByIds(Student.class, ids);   // 超过 1000 条自动分批

// 下拉选项
Map<String, String> options = sql.options(Academy.class);
```

### 5. 事务

```java
sql.transaction(tx -> {
    tx.insert(a);
    tx.insert(b);
});                              // 正常返回提交，抛异常回滚
```

事务只在当前线程可见，事务内通过同一个会话发起的调用自动复用连接；
嵌套调用并入外层事务，由最外层决定成败。事务内另起线程不在保护范围内——这是 JDBC 的语义。

### 6. 执行手写 SQL

需要写框架覆盖不到的 SQL 时，仍然可以拿到参数绑定与方言：

```java
SqlStatement statement = new SqlStatement(
        "SELECT COUNT(*) FROM " + sql.tableName(Student.class) + " WHERE score >= ?",
        List.of(3));

Object count = sql.queryScalar(statement);
List<String> names = sql.query(
        new SqlStatement("SELECT student_name FROM \"student\" WHERE score > ?", List.of(3)),
        rs -> rs.getString(1));
```

参数一律走占位符，`SqlStatement` 保证参数顺序与 `?` 一一对应。

## 自动建表

```java
SchemaSync.Report report = sql.synchronizeSchema(Academy.class, Student.class);
report.changed();     // 有没有改动
report.executed();    // 实际执行的 DDL

sql.synchronizeSchema(true, Student.class);   // 演练：只打印，不执行
```

规则很保守：

- 表不存在 → `CREATE TABLE`，整数主键按方言生成自增列；
- 表存在但缺列 → `ALTER TABLE ... ADD COLUMN`，新增列一律允许为空；
- **不改列类型、不删列**。这类操作可能截断或丢失数据，必须由人决定。

## 数据库支持

| 数据库 | 方言 | 说明 |
| --- | --- | --- |
| PostgreSQL | `PostgresDialect` | 标识符加双引号，`SERIAL` / `BIGSERIAL` |
| MySQL / MariaDB | `MySQLDialect` | `AUTO_INCREMENT` |
| H2 | `H2Dialect` | 测试用，`AUTO_INCREMENT` |
| 其它 | `Dialect.DEFAULT` | 不加引号、`LIMIT ? OFFSET ?` |

需要新数据库时实现 `Dialect` 即可，接口只有六个点：引号规则、分页子句、自增列声明、
Java 类型到列类型的映射、表存在性查询、列清单查询。

## 校验

`insert` / `update` 默认先校验再写库：

```java
@ValidatorNotNull   @ValidatorLength(min, max)
@ValidatorPattern(regexp)   @ValidatorRange(min, max)   @ValidatorModel
```

需要读库的跨字段规则，在模型类上写约定的方法：

```java
// 返回空串表示通过
public String checker(Student self, Student before, CheckerType type) { ... }
```

`before` 是更新前从库里读出的快照，新增时为 `null`。
批量导入已清洗过的数据时，可以关掉校验：

```java
new ModelSession(provider, "", dialect, false);
```

## 设计取舍

- **不用 MyBatis**：SQL 由 `Query` / `SqlStatement` 生成，不存在 XML 映射文件，
  也没有「动态标签」这一层。条件、排序、分页都是 Java 方法。
- **不依赖 Spring**：连接来源由 `ConnectionProvider` 抽象，给 `DataSource`、
  给 URL 都行；宿主用 Spring 时可以用 `DataSourceUtils` 代理实现它，框架侧无感知。
- **不含 Web 层**：这是 SQL 引擎，不是脚手架。页面、控制器、模板都不在范围内。
  显示用的 `${字段}` 模板（`@ModelClass.text`）保留，仅用于生成显示文本与下拉标签。
- **保守 DDL**：只建表、只补列，不做破坏性变更。
- **真机验证**：方言相关的逻辑用真实 PostgreSQL 跑集成测试，
  连不上数据库时整类跳过，不会把 CI 打红。

## C++ 版

同一套思路有一个 C++17 移植，放在 [`source/`](source/)：零外部依赖（标准库 + 内嵌 SQLite），
用成员指针表代替注解反射。

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
Student student;
student.name = "张三";
students.insert(student);                       // student.id 自动回填

auto adults = students.selectList(
    students.query().where(ipage::field("age") >= 18));
```

```bash
cd source && make          # 构建并跑测试（Windows 上用 mingw32-make）
```

细节见 [`source/README.md`](source/README.md)。

## 构建

```bash
./mvnw package              # 产出 target/ipage-1.0.0.jar 与 -sources.jar
./mvnw test                 # 37 个用例；没有 PostgreSQL 时相关用例自动跳过
```

集成测试默认连 `127.0.0.1:5432`，账号 `postgres`，会在库里建 / 删 `ipage_test` 与 `ipage_api_test`
两个临时库。改连接参数请编辑 `src/test/java/com/rabbitc/ipage/jdbc/*IntegrationTest.java` 顶部的常量。

## 目录

```
src/main/java/com/rabbitc/ipage/
├── annotation/    @ModelClass、@ModelField、5 个校验注解
├── dialect/       Dialect 接口与 PostgreSQL / MySQL / H2 实现
├── enums/         FieldType、OperatorType、SortType、SqlFunction ...
├── exception/     ModelDefinitionException、CheckerException ...
├── jdbc/          ConnectionProvider、ModelSession、RowMapper、Page、SchemaSync
├── meta/          ModelPermission
├── reflect/       FieldDescriptor、ModelMeta、ModelValidator、TemplateRenderer、ValueConverter
└── sql/           Query、SqlStatement、SqlCondition、SelectedField、JoinCondition

source/                    C++17 版
├── ipage/         field / model / dialect / sql / connection / session / schema
├── sqlite/        SQLite amalgamation
├── examples/      用法示例
└── tests/         端到端测试
```

## 许可

见 [LICENSE](LICENSE)。
