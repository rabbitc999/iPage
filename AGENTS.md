# AGENTS.md

给后续维护者（包括 AI agent）的项目约定。改代码前先读这一页。

## 项目定位

ipage 是一个**注解驱动的 SQL 引擎**，不是 Web 框架、不是脚手架、不是 ORM 全家桶。

判断一个功能该不该加，看这句话：**它是否让「用 Java 类描述表并生成 SQL」这件事更可靠？**
页面渲染、请求分发、参数绑定到 HTTP、登录鉴权，都不在范围内，不要加回来。

历史包袱说明：这个项目最初从 `ipage-student` 里内嵌的包抽出来，早期的 `web/`、`spring/`、
`template/`、`html/` 目录已经全部删除。看到「以前好像有 web 层」的说法时，以本页为准。

## 依赖红线

- 运行时只允许 `slf4j-api`。
- 数据库驱动一律 `optional`：框架只用 JDBC 接口。
- **不要引入 Spring**（任何 artifact，包括 `spring-jdbc` 提供的工具类）。
- **不要引入 MyBatis**、Hibernate 或任何 ORM。
- 测试依赖（`junit-jupiter`、`h2`）只在 `test` scope。

改完 `pom.xml` 用 `./mvnw dependency:tree` 自查一遍。

## 构建

```bash
# 本机 JAVA_HOME 配到了 bin，需要覆盖；正常环境可省略
JAVA_HOME="C:\Program Files\Java\jdk-22" ./mvnw test
JAVA_HOME="C:\Program Files\Java\jdk-22" ./mvnw package -DskipTests
```

- Java 17（`maven.compiler.release=17`）。
- 没有全局 `mvn` 时用仓库里的 wrapper（`.mvn/` + `mvnw`）。
- 缺少插件时去掉 `-o`（离线模式会找不到 `maven-resources-plugin`）。

### C++ 版（`source/`）

```bash
cd source
mingw32-make            # Windows；Linux/macOS 用 make。构建 + 跑测试
cmake -S . -B build -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release
cmake --build build && ctest --test-dir build --output-on-failure
```

- C++17（`-std=c++17 -Wall -Wextra`），只用标准库 + 内嵌的 `sqlite/sqlite3.c`。
- `sqlite3.c` 有 9 MB，编译要 20 秒左右，改动它不现实——它是官方 amalgamation，原样保留。
- 两个构建入口（`Makefile` / `CMakeLists.txt`）**都要保持可用**，改结构时两边一起改。

## 测试约定

- `SqlStatementTest`：纯字符串断言，不连库，跑得最快。
- `SchemaSyncTest`：H2，验证建表 / 补列逻辑。
- `PostgresIntegrationTest`、`SessionApiTest`：真实 PostgreSQL。
  连不上时 `assumeTrue` 整类跳过，**不要改成 `fail`**——CI 和同事机器上不一定有库。

真机测试是这套代码质量的主要保障。历史上 H2 放过了 6 个 PostgreSQL 会拒绝的缺陷
（参数未绑定、标识符未加引号、别名与 JDBC 标签不一致、外键方向、HAVING 引用别名、null 参数），
所以**只要动了 SQL 生成或方言，就必须在真机上跑一遍**。

写集成测试时注意执行顺序：`@Order` 之间有状态依赖（库是按顺序建表、写数据的），
改断言前先确认前面的用例留下了什么。曾因为「前一个用例已经建过同名表」而误判过前缀逻辑。

## 代码风格

- 源码是 UTF-8，注释和日志用中文，`javadoc` 写全（`@param`、`@return`、`@throws`）。
- 不使用 `var` 做字段类型推断以外的花哨写法；不引入 Lombok。
- 工具方法优先 `private static`，不要把内部细节暴露成公开 API。
- 判断字段「代表哪一列」一律查 `FieldDescriptor` / `ModelMeta`，不要在字符串上猜。

## 五个必须遵守的设计约束

### 1. 标识符统一走方言

表名、列名、别名要进 SQL 时，必须经过 `Dialect.quote(...)`：

```java
dialect.quote(meta.table())          // 对
"student"                            // 错，PostgreSQL 上表名可能建得出来查不到
```

只有「结果集列标签」这一处例外：`SelectedField.alias()` 返回未加引号的原名，
由 `SqlStatement` 在渲染时加引号。别把这层反过来。

### 2. 参数只能走占位符

任何来自调用方的值都必须进 `parameters` 列表，不允许拼接进 SQL 文本。
需要通配符时用 `CONCAT('%', ?, '%')`（见 `SqlStatement.Builder.condition`）。

表名前缀、列名这类「配置项」在拼之前先过 `isSafeIdentifier` / `SAFE_PREFIX`。

### 3. 表名只有一个来源：`ModelMeta.table()`

建表（`SchemaSync`）、查询（`Query`）、写入（`SqlStatement.insert/update/delete`）
都从 `meta.table()` 取表名。带前缀时，`ModelSession.metaOf()` 返回的是
「表名已改写」的元数据副本（按「类 + 实际表名」缓存），**不在 SQL 文本上打补丁**。

这条规则是踩过坑总结的：早先用正则给 `FROM x` 补前缀，遇到 `FROM "x"`（已加引号）就失效，
表现为「建表建了 `api_step`，插入却插到 `step`」。不要走回头路。

手写 SQL 的调用方要用 `session.tableName(Class)` 取实际表名。

### 4. 别名不能等于表名，也不能撞 SQL 保留字

别名统一加 `t_` 前缀（`Query.Aliases.PREFIX`）。不能省。

真机踩到的：表名 `user` 时别名也是 `user`，PostgreSQL 会把 `user."user_id"` 里的
`user` 当作 `CURRENT_USER`，报「语法错误 在 "." 或附近」。又因为 `user` 同时是
内置视图名，不加引号的 `SELECT ... FROM user` 会**静默查错表**。
加前缀比维护各方言关键字表省事，也不会漏。

### 5. 有聚合时，GROUP BY 必须覆盖所有非聚合选择列

`Query.selectAggregates()` 里先加主表主键，再把 `selectedFields` 中所有
`kind() != AGGREGATE` 的 `columnExpression()` 加进去。

只按主键分组是错的：`joinAssociations()` 会把关联表（如 `t_sclass`）的**所有列**
也放进 SELECT，PostgreSQL 严格要求它们出现在 GROUP BY 里，否则报
「字段必须出现在 GROUP BY 子句里或在聚合函数中使用」。

主键在前可保证分组顺序稳定；主键相同的行在各关联表上取值唯一，不会多出行。

---

## C++ 版的对应约束

`source/` 是上面这套设计的 C++17 移植，五条约束在它身上同样成立，对应位置如下：

| 约束 | C++ 里的落点 |
| --- | --- |
| 标识符走方言 | `Query::build()` 里所有表名/列名都经 `Dialect::quote` / `qualify`，没有裸拼接 |
| 参数只走占位符 | `Cell` 是唯一值载体，`Condition::render` 边拼边 `params.push_back` |
| 表名只有一个来源 | `Session` 全部从 `metaOf<T>().table()` 取；`SchemaSync` 同样 |
| 别名加 `t_` 前缀 | `Query::kMainAlias = "t_"`，关联表 `t_2`、`t_3`…… |
| GROUP BY 覆盖非聚合列 | `Query::build()` 遍历 `selections_`，凡 `!function.has_value()` 的一律进 GROUP BY |

额外的 C++ 侧约定：

- **列白名单**：`Query::filter` / `orderBy` / `select` 的列名必须能在 `ModelMeta` 里查到，
  查不到抛 `std::invalid_argument`。不要为了「支持表别名前缀」而放开这个检查。
- **两层结构不能塌**：`Field<T,M>`（声明期，带成员指针）只存在于 `IPAGE_MODEL` 展开处；
  `FieldInfo`（运行期，类型擦除）才是内核看到的。把 `Query`/`Session` 模板化会显著增加编译时间。
- **注释里不要出现 `/*`**（写成 `jdbc 包` 而不是 `jdbc/*`），GCC 会报 `-Wcomment`。
- 公开头文件一律 `#ifndef IPAGE_XXX_HPP` 保护，`sqlite3.h` 只允许出现在 `connection.cpp`。

## 已删除、不要恢复的东西

| 已删除 | 原因 |
| --- | --- |
| `web/`（`IpagRequest`、`IpagResponse`、`IpagHandler`） | 项目是 SQL 引擎，HTTP 层由宿主做 |
| `spring/`（`IpagConfiguration` 等） | 不依赖 Spring |
| `template/` 自制 HTML 模板引擎 | 属于 Web 层 |
| `src/main/resources/ipage/*.html`、css | 同上 |
| `annotation/EnableIpag` | Spring 注解 |

`reflect/TemplateRenderer` 保留：它只做 `${字段}` 的显示文本替换，
服务于 `@ModelClass.text`（下拉标签、日志），与页面模板无关。

## 修改检查清单

提交前逐项确认：

- [ ] `./mvnw test` 全绿（至少 37 个用例，真机用例没有被跳过太多）
- [ ] 动了 SQL 生成 → 看过 `PostgresIntegrationTest` 里生成的 SQL 文本
- [ ] 动了方言 → 三种方言一起想一遍（MySQL 不加引号、PG 加引号、H2 大写折叠）
- [ ] 新增公开 API → 有 javadoc，有至少一个用例覆盖
- [ ] 没有引入新依赖（`dependency:tree` 只有 `slf4j-api`）
- [ ] 没有把 Web / Spring / MyBatis 相关的类加回来
