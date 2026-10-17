package com.rabbitc.ipage.dialect;

import com.rabbitc.ipage.annotation.ModelClass;
import com.rabbitc.ipage.annotation.ModelField;
import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.jdbc.ConnectionProvider;
import com.rabbitc.ipage.jdbc.ModelSession;
import com.rabbitc.ipage.jdbc.SchemaSync;
import com.rabbitc.ipage.reflect.ModelMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言与建表。
 * <p>
 * DDL 的文本断言用 PostgreSQL 方言做（这是本项目的主要目标库），
 * 执行层面的验证用 H2 内存库跑真实语句——两者语法在框架用到的范围内一致。
 */
class SchemaSyncTest {

    /**
     * 学生。
     */
    @ModelClass(name = "学生", table = "student")
    public static class Student {

        @ModelField(column = "student_id", type = FieldType.MODEL_ID, fixed = true)
        Integer studentId;

        @ModelField(column = "student_name", type = FieldType.STRING, text = "姓名")
        String studentName;

        @ModelField(column = "birthday", type = FieldType.DATE, text = "生日")
        LocalDate birthday;

        @ModelField(column = "score", type = FieldType.NUMBER, text = "成绩")
        BigDecimal score;

        @ModelField(column = "enrolled_at", type = FieldType.DATE, text = "入学时间")
        LocalDateTime enrolledAt;
    }

    /**
     * 自增主键的模型。
     */
    @ModelClass(name = "课程", table = "course")
    public static class Course {

        @ModelField(column = "course_id", type = FieldType.MODEL_ID)
        Long courseId;

        @ModelField(column = "course_name", type = FieldType.STRING)
        String courseName;
    }

    @Test
    @DisplayName("PostgreSQL 方言给标识符加双引号")
    void postgresQuotesIdentifiers() {
        Dialect dialect = PostgresDialect.INSTANCE;
        assertEquals("\"student_name\"", dialect.quote("student_name"));
        assertEquals("\"a\"\"b\"", dialect.quote("a\"b"));
        assertEquals("student_name", MySQLDialect.INSTANCE.quote("student_name"));
    }

    @Test
    @DisplayName("PostgreSQL 建表用 SERIAL，fixed 主键不自增")
    void postgresCreateTable() {
        SchemaSync sync = new SchemaSync(null, PostgresDialect.INSTANCE, true);
        String ddl = sync.createTable(ModelMeta.of(Course.class));
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS \"course\""), ddl);
        assertTrue(ddl.contains("\"course_id\" BIGSERIAL PRIMARY KEY"), ddl);

        String fixedDdl = sync.createTable(ModelMeta.of(Student.class));
        assertTrue(fixedDdl.contains("\"student_id\" INTEGER PRIMARY KEY"), fixedDdl);
        assertTrue(fixedDdl.contains("\"student_name\" VARCHAR(255)"), fixedDdl);
        assertTrue(fixedDdl.contains("\"birthday\" DATE"), fixedDdl);
        assertTrue(fixedDdl.contains("\"score\" NUMERIC(18, 4)"), fixedDdl);
        assertTrue(fixedDdl.contains("\"enrolled_at\" TIMESTAMP"), fixedDdl);
    }

    @Test
    @DisplayName("方言探测按产品名识别 PostgreSQL")
    void detectsPostgres() {
        assertTrue(Dialects.byProductName("PostgreSQL") instanceof PostgresDialect);
        assertTrue(Dialects.byProductName("H2") instanceof H2Dialect);
        assertTrue(Dialects.byProductName("MySQL") instanceof MySQLDialect);
        assertTrue(Dialects.byProductName(null) instanceof MySQLDialect);
    }

    @Test
    @DisplayName("H2 上真实建表、补列、再同步幂等")
    void createsAndEvolvesTable() throws Exception {
        String url = "jdbc:h2:mem:ipage_schema;DB_CLOSE_DELAY=-1";
        ConnectionProvider provider = () -> DriverManager.getConnection(url, "sa", "");
        ModelSession session = new ModelSession(provider, H2Dialect.INSTANCE);

        SchemaSync.Report first = session.synchronizeSchema(Student.class);
        assertTrue(first.changed());
        assertEquals(1, first.count(), first.toString());

        // 第二次不应再产生 DDL
        SchemaSync.Report second = session.synchronizeSchema(Student.class);
        assertFalse(second.changed(), second.toString());

        // 手工删一列后再同步，应当只补那一列
        try (Connection connection = provider.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE student DROP COLUMN score");
        }
        SchemaSync.Report third = session.synchronizeSchema(Student.class);
        assertEquals(1, third.count(), third.toString());
        assertTrue(third.executed().get(0).contains("score"), third.toString());

        try (Connection connection = provider.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = SCHEMA()"
                     + " AND UPPER(TABLE_NAME) = 'STUDENT'")) {
            assertTrue(result.next());
            assertEquals(5, result.getInt(1));
        }
    }

    @Test
    @DisplayName("演练模式不落库")
    void dryRunDoesNotExecute() throws Exception {
        String url = "jdbc:h2:mem:ipage_dry;DB_CLOSE_DELAY=-1";
        ConnectionProvider provider = () -> DriverManager.getConnection(url, "sa", "");
        SchemaSync sync = new SchemaSync(provider, H2Dialect.INSTANCE, true);
        SchemaSync.Report report = sync.synchronize(List.of(ModelMeta.of(Course.class)));
        assertTrue(report.changed());
        assertTrue(report.executed().get(0).startsWith("[dry-run]"), report.toString());

        ModelSession session = new ModelSession(provider, H2Dialect.INSTANCE);
        SchemaSync.Report real = session.synchronizeSchema(Course.class);
        assertTrue(real.changed(), "演练后表仍应不存在，真实同步要能建出来");
    }

    @Test
    @DisplayName("建表后可以写入并读回")
    void insertsAfterCreate() throws Exception {
        String url = "jdbc:h2:mem:ipage_rw;DB_CLOSE_DELAY=-1";
        ConnectionProvider provider = () -> DriverManager.getConnection(url, "sa", "");
        ModelSession session = new ModelSession(provider, H2Dialect.INSTANCE);
        session.synchronizeSchema(Course.class);

        Course course = new Course();
        course.courseName = "高等数学";
        assertEquals(1, session.insert(course));

        Map<String, String> options = session.options(Course.class);
        assertEquals(1, options.size());
        assertTrue(options.values().stream().anyMatch(v -> v.equals("高等数学") || v.isEmpty()));
    }
}
