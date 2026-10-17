package com.rabbitc.ipage.jdbc;

import com.rabbitc.ipage.annotation.ModelClass;
import com.rabbitc.ipage.annotation.ModelField;
import com.rabbitc.ipage.dialect.Dialect;
import com.rabbitc.ipage.dialect.Dialects;
import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.enums.OperatorType;
import com.rabbitc.ipage.enums.SortType;
import com.rabbitc.ipage.enums.SqlFunction;
import com.rabbitc.ipage.reflect.ModelMeta;
import com.rabbitc.ipage.sql.Query;
import com.rabbitc.ipage.sql.SqlStatement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真机跑 PostgreSQL。
 * <p>
 * 只要本机 {@code 127.0.0.1:5432} 有一个账号密码为 {@code postgres/123456} 的实例，
 * 这个测试就会建一个 {@code ipage_test} 库、建表、写入、查询、再删表；连不上则整类跳过，
 * 这样在 CI 或没有数据库的机器上不会把构建打红。
 * <p>
 * 它验证的是方言里那些「只在 PostgreSQL 上才暴露」的问题：
 * 标识符大小写、{@code SERIAL} 自增回填、{@code NUMERIC}/{@code BOOLEAN} 的读写、
 * 以及带引号的表名在 JOIN 与分页里是否仍然拼得对。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresIntegrationTest {

    private static final String ADMIN_URL = "jdbc:postgresql://127.0.0.1:5432/postgres";
    private static final String DB = "ipage_test";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String USER = "postgres";
    private static final String PASSWORD = "123456";

    private static boolean available;
    private static ModelSession session;

    /**
     * 学院。
     */
    @ModelClass(name = "学院", table = "academy", text = "${academyName}")
    public static class Academy {

        @ModelField(column = "academy_id", type = FieldType.MODEL_ID)
        Long academyId;

        @ModelField(column = "academy_name", type = FieldType.STRING, text = "学院名")
        String academyName;

        @ModelField(column = "academy_code", type = FieldType.STRING, text = "代码")
        String academyCode;
    }

    /**
     * 学员。字段里刻意混入大写、下划线、布尔、金额、时间，覆盖 PostgreSQL 上最容易出问题的类型。
     */
    @ModelClass(name = "学员", table = "pg_student", text = "${studentName}")
    public static class Student {

        @ModelField(column = "student_id", type = FieldType.MODEL_ID)
        Long studentId;

        @ModelField(column = "student_name", type = FieldType.STRING, text = "姓名")
        String studentName;

        @ModelField(column = "academy_id", type = FieldType.ASSOCIATION_ID, byModel = Academy.class, text = "学院")
        Long academyId;

        @ModelField(column = "academy_id", type = FieldType.ASSOCIATION_MODEL, byModel = Academy.class, text = "学院")
        Academy academy;

        @ModelField(column = "score", type = FieldType.NUMBER, text = "绩点")
        BigDecimal score;

        @ModelField(column = "active", type = FieldType.BOOLEAN, text = "在读")
        Boolean active;

        @ModelField(column = "birthday", type = FieldType.DATE, text = "生日")
        LocalDate birthday;

        @ModelField(column = "enrolled_at", type = FieldType.DATE, text = "入学时间")
        LocalDateTime enrolledAt;
    }

    /**
     * 学院 + 人数统计。
     */
    @ModelClass(name = "学院统计", table = "academy")
    public static class AcademyStat {

        @ModelField(column = "academy_id", type = FieldType.MODEL_ID)
        Long academyId;

        @ModelField(column = "academy_name", type = FieldType.STRING, text = "学院名")
        String academyName;

        @ModelField(column = "student_id", type = FieldType.ASSOCIATION_MODEL, byModel = Student.class, text = "学员")
        Student student;

        @ModelField(column = "student_id", type = FieldType.AGGREGATION, byModel = Student.class,
                function = SqlFunction.COUNT, useRelatedForeignKey = true, text = "人数")
        Integer studentCount;
    }

    @BeforeAll
    static void connect() {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD)) {
            available = connection.isValid(2);
        } catch (Exception e) {
            available = false;
            return;
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB);
            statement.execute("CREATE DATABASE " + DB + " ENCODING 'UTF8'");
        } catch (Exception e) {
            available = false;
            return;
        }
        ConnectionProvider provider = () -> DriverManager.getConnection(URL, USER, PASSWORD);
        Dialect dialect;
        try (Connection probe = provider.getConnection()) {
            dialect = Dialects.detect(probe);
        } catch (Exception e) {
            available = false;
            return;
        }
        session = new ModelSession(provider, dialect);
    }

    @AfterAll
    static void cleanup() {
        if (!available) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        } catch (Exception ignored) {
            // 测试库残留不影响构建：下次 @BeforeAll 会先 DROP 再建
        }
    }

    @Test
    @Order(1)
    @DisplayName("自动探测到 PostgreSQL 方言")
    void detectsDialect() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        assertEquals("PostgreSQL", session.dialect().name());
        assertTrue(session.dialect().quotesIdentifiers());
    }

    @Test
    @Order(2)
    @DisplayName("动态建表：按模型自动 CREATE TABLE")
    void createsTables() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        SchemaSync.Report report = session.synchronizeSchema(Academy.class, Student.class);
        assertTrue(report.changed(), report.toString());
        assertEquals(2, report.count(), report.toString());
        assertTrue(report.executed().get(0).contains("\"academy_id\" BIGSERIAL PRIMARY KEY"),
                report.executed().get(0));

        // 再同步一次不应产生任何 DDL
        SchemaSync.Report again = session.synchronizeSchema(Academy.class, Student.class);
        assertFalse(again.changed(), again.toString());
    }

    @Test
    @Order(3)
    @DisplayName("写入后自增主键回填到实体")
    void insertsAndBackfillsId() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Academy academy = new Academy();
        academy.academyName = "计算机学院";
        academy.academyCode = "CS";
        assertEquals(1, session.insert(academy));
        assertNotNull(academy.academyId, "BIGSERIAL 生成的主键应回填到实体");

        Academy second = new Academy();
        second.academyName = "外国语学院";
        second.academyCode = "FL";
        session.insert(second);
        assertTrue(second.academyId > academy.academyId);
    }

    @Test
    @Order(4)
    @DisplayName("含中文、金额、布尔、时间的往返读写")
    void roundTripsTypes() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Query academyQuery = Query.of(Academy.class, session.dialect()).where("academyCode", "CS");
        Long academyId = session.selectOne(academyQuery, RowMapper.of(academyQuery))
                .map(a -> ((Academy) a).academyId).orElseThrow();

        Student student = new Student();
        student.studentName = "张三";
        student.academyId = academyId;
        student.score = new BigDecimal("3.75");
        student.active = Boolean.TRUE;
        student.birthday = LocalDate.of(2004, 9, 1);
        student.enrolledAt = LocalDateTime.of(2023, 9, 1, 8, 30, 0);
        assertEquals(1, session.insert(student));

        Student loaded = session.findById(Student.class, student.studentId).orElseThrow();
        assertEquals("张三", loaded.studentName);
        assertEquals(0, new BigDecimal("3.75").compareTo(loaded.score), "NUMERIC 往返不应丢精度");
        assertEquals(Boolean.TRUE, loaded.active);
        assertEquals(LocalDate.of(2004, 9, 1), loaded.birthday);
        assertEquals(LocalDateTime.of(2023, 9, 1, 8, 30, 0), loaded.enrolledAt);
    }

    @Test
    @Order(5)
    @DisplayName("JOIN 关联对象与模糊查询")
    void joinsWithCondition() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Query query = Query.of(Student.class, session.dialect()).where("studentName", OperatorType.LIKE, "张");
        List<Object> rows = session.selectList(query, RowMapper.of(query));
        assertEquals(1, rows.size());

        Student student = (Student) rows.get(0);
        assertNotNull(student.academy, "关联对象应被填上，而不是 null");
        assertEquals("计算机学院", student.academy.academyName);
        assertEquals("CS", student.academy.academyCode);
    }

    @Test
    @Order(6)
    @DisplayName("分页、排序与总数")
    void pagesAndSorts() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Query other = Query.of(Academy.class, session.dialect()).where("academyCode", "FL");
        Long foreignAcademyId = session.selectOne(other, RowMapper.of(other))
                .map(row -> ((Academy) row).academyId).orElseThrow();

        Student second = new Student();
        second.studentName = "李四";
        second.active = Boolean.FALSE;
        second.score = new BigDecimal("2.10");
        second.academyId = foreignAcademyId;
        session.insert(second);

        Query query = Query.of(Student.class, session.dialect())
                .orderBy("studentName", SortType.ASC);
        Page<Object> page = session.selectPage(query, RowMapper.of(query), 1, 1);
        assertEquals(2, page.total());
        assertEquals(1, page.rows().size());
        assertTrue(page.hasNext());
        // 字符串按码位比较：'李'(U+674E) < '张'(U+5F20) 的常见排序里，李四在前
        assertEquals("李四", ((Student) page.rows().get(0)).studentName);

        Query descending = Query.of(Student.class, session.dialect()).orderBy("studentName", SortType.DESC);
        List<Object> first = session.selectList(descending.page(1, 1), RowMapper.of(descending));
        assertEquals("张三", ((Student) first.get(0)).studentName);
    }

    @Test
    @Order(7)
    @DisplayName("聚合统计与 HAVING")
    void aggregates() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Query query = Query.of(AcademyStat.class, session.dialect());
        List<Object> rows = session.selectList(query, RowMapper.of(query));
        assertEquals(2, rows.size(), "两个学院都应在结果里");
        int total = rows.stream()
                .map(row -> (AcademyStat) row)
                .mapToInt(stat -> stat.studentCount == null ? 0 : stat.studentCount)
                .sum();
        assertEquals(2, total, "两个学员的计数应为 2");

        Query filtered = Query.of(AcademyStat.class, session.dialect()).where("studentCount", OperatorType.GTE, 1);
        List<Object> onlyWithStudents = session.selectList(filtered, RowMapper.of(filtered));
        assertEquals(2, onlyWithStudents.size(), "两个学院各有一个学员");
    }

    @Test
    @Order(8)
    @DisplayName("更新与按主键删除")
    void updatesAndDeletes() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Query query = Query.of(Student.class, session.dialect()).where("studentName", "李四");
        Student student = (Student) session.selectOne(query, RowMapper.of(query)).orElseThrow();
        student.score = new BigDecimal("1.50");
        assertEquals(1, session.update(student));

        Student reloaded = session.findById(Student.class, student.studentId).orElseThrow();
        assertEquals(0, new BigDecimal("1.50").compareTo(reloaded.score));

        assertEquals(1, session.deleteById(Student.class, student.studentId));
        assertTrue(session.findById(Student.class, student.studentId).isEmpty());
    }

    @Test
    @Order(9)
    @DisplayName("下拉选项取显示模板里声明的字段")
    void optionsUseDisplayTemplate() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Map<String, String> options = session.options(Academy.class);
        assertEquals(2, options.size());
        assertTrue(options.containsValue("计算机学院"), options.toString());
    }

    @Test
    @Order(10)
    @DisplayName("迁移：给已有表补一列，不动已有数据")
    void addsMissingColumn() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE \"academy\" DROP COLUMN \"academy_code\"");
        }
        SchemaSync.Report report = session.synchronizeSchema(Academy.class);
        assertTrue(report.changed(), report.toString());
        assertEquals(1, report.count(), report.toString());
        assertTrue(report.executed().get(0).contains("academy_code"), report.executed().get(0));

        // 数据没丢
        Query query = Query.of(Academy.class, session.dialect()).where("academyName", "计算机学院");
        List<Object> rows = session.selectList(query, RowMapper.of(query));
        assertEquals(1, rows.size());
    }

    @Test
    @Order(11)
    @DisplayName("PostgreSQL 上生成的 SQL 引号闭合且占位符与参数一一对应")
    void sqlIsWellFormed() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        Query query = Query.of(Student.class, session.dialect())
                .where("studentName", OperatorType.STARTS_WITH, "张")
                .where("active", OperatorType.EQ, true)
                .orderBy("studentId", SortType.DESC)
                .page(2, 10);
        SqlStatement statement = SqlStatement.select(query, true, session.dialect());
        String sql = statement.sql();
        assertEquals(4, statement.parameters().size(), sql);
        assertEquals(4, sql.chars().filter(c -> c == '?').count(), sql);
        assertTrue(sql.contains("academy.\"academy_name\""), sql);
        assertTrue(sql.endsWith("LIMIT ? OFFSET ?"), sql);
    }

    @Test
    @Order(12)
    @DisplayName("元数据缓存不会把不同模型的表搞混")
    void metadataIsPerModel() {
        assertEquals("academy", ModelMeta.of(Academy.class).table());
        assertEquals("pg_student", ModelMeta.of(Student.class).table());
    }

    @Test
    @Order(13)
    @DisplayName("条件语法糖：like / in / between 生成合法 SQL 且能真查到数据")
    void conditionSugar() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");

        Query byName = Query.of(Student.class, session.dialect()).like("studentName", "张");
        assertEquals(1, session.count(byName), "like 应生成 LIKE '%张%'");

        Query byId = Query.of(Student.class, session.dialect()).in("studentId", java.util.List.of(-1L, -2L));
        assertEquals(0, session.count(byId), "in 应生成 IN (...)");

        Query emptyIn = Query.of(Student.class, session.dialect()).in("studentId", java.util.List.of());
        assertEquals(1, session.count(emptyIn), "空的 in 应被忽略，而不是变成 IN ()");

        Query byScore = Query.of(Student.class, session.dialect()).between("score", 1, 5);
        assertEquals(1, session.count(byScore), "between 两端都闭");

        Query onlyFrom = Query.of(Student.class, session.dialect()).between("score", 3, null);
        assertEquals(1, session.count(onlyFrom), "只给下界时只加 GTE");

        SqlStatement statement = SqlStatement.select(byScore, true, session.dialect());
        assertEquals(statement.parameters().size(),
                statement.sql().chars().filter(c -> c == '?').count(), statement.sql());
    }
}
