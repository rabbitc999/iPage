package com.rabbitc.ipage.jdbc;

import com.rabbitc.ipage.annotation.ModelClass;
import com.rabbitc.ipage.annotation.ModelField;
import com.rabbitc.ipage.annotation.ValidatorNotNull;
import com.rabbitc.ipage.dialect.Dialects;
import com.rabbitc.ipage.enums.CheckerType;
import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.enums.OperatorType;
import com.rabbitc.ipage.enums.SortType;
import com.rabbitc.ipage.exception.CheckerException;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 接入层：候选入口、事务、校验、拼接 SQL。
 * <p>
 * 与 {@code PostgresIntegrationTest} 的分工：那边验证「方言对不对」，这里验证「用起来顺不顺」——
 * 最小入口能不能两行跑起来、事务是不是真的回滚、校验是不是在写库之前拦住。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionApiTest {

    private static final String ADMIN_URL = "jdbc:postgresql://127.0.0.1:5432/postgres";
    private static final String DB = "ipage_api_test";
    private static final String PASSWORD = "123456";

    private static boolean available;
    private static String url;
    private static ConnectionProvider provider;

    /**
     * 教室。表名刻意使用与类名不同的名字，用来验证「注解表名」而不是「类名」生效。
     */
    @ModelClass(name = "教室", table = "api_room", text = "${roomName}")
    public static class Room {

        @ModelField(column = "room_id", type = FieldType.MODEL_ID)
        Long roomId;

        @ModelField(column = "room_name", type = FieldType.STRING, text = "教室名")
        @ValidatorNotNull(message = "教室名不能为空")
        String roomName;

        @ModelField(column = "capacity", type = FieldType.NUMBER, text = "容量")
        Integer capacity;

        /**
         * 自定义校验挂载点：容量不能超过 200。
         * <p>
         * 返回空串表示通过，返回其它内容即视为失败——这是框架约定的协议，
         * 名字与签名都不能改，注释里说明清楚是为了让后来人不要「顺手重命名」。
         *
         * @param before 修改前的快照
         * @param type   操作类型
         * @return 错误信息，空串表示通过
         */
        public String checker(Room before, CheckerType type) {
            if (capacity != null && capacity > 200) {
                return "旧值 " + (before == null ? "无" : before.capacity) + "，容量超过 200";
            }
            return "";
        }
    }

    /**
     * 步骤表，只用于「前缀把表名补到注解表名之前」这个用例。
     */
    @ModelClass(name = "步骤", table = "step")
    public static class Step {

        @ModelField(column = "step_id", type = FieldType.MODEL_ID)
        Long stepId;

        @ModelField(column = "label", type = FieldType.STRING, text = "名称")
        String label;

        @ModelField(column = "sequence", type = FieldType.NUMBER, text = "序号")
        Integer sequence;
    }

    @BeforeAll
    static void prepare() {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, "postgres", PASSWORD)) {
            available = connection.isValid(2);
        } catch (Exception e) {
            available = false;
            return;
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, "postgres", PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " ENCODING 'UTF8'");
        } catch (Exception e) {
            available = false;
            return;
        }
        url = "jdbc:postgresql://127.0.0.1:5432/" + DB;
        provider = ConnectionProvider.of(url, "postgres", PASSWORD);
    }

    @AfterAll
    static void cleanup() {
        if (!available) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, "postgres", PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        } catch (Exception ignored) {
            // 测试库残留不影响构建：下次 @BeforeAll 会先 DROP 再建
        }
    }

    @Test
    @Order(1)
    @DisplayName("最小入口：给 URL 与账号即可，方言自动探测")    void minimalEntry() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(url, "postgres", PASSWORD);
        assertEquals("PostgreSQL", session.dialect().name());
        assertTrue(session.validating(), "默认应在写库前校验");
        assertEquals("", session.tablePrefix());
    }

    @Test
    @Order(2)
    @DisplayName("自动建表 + 写入 + 按查询对象取实体，全程不需要手写 RowMapper")
    void createAndRead() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);
        SchemaSync.Report report = session.synchronizeSchema(Room.class);
        assertTrue(report.changed(), report.toString());

        Room room = new Room();
        room.roomName = "一教 101";
        room.capacity = 60;
        assertEquals(1, session.insert(room));
        assertNotNull(room.roomId, "自增主键应回填");

        List<Room> all = session.selectList(Query.of(Room.class, session.dialect()));
        assertEquals(1, all.size());
        assertEquals("一教 101", all.get(0).roomName);

        Optional<Room> found = session.findById(Room.class, room.roomId);
        assertTrue(found.isPresent());
        assertEquals(Integer.valueOf(60), found.get().capacity);
    }

    @Test
    @Order(3)
    @DisplayName("校验不通过时不写库，异常里带自定义信息")
    void validationBlocksWrite() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);

        Room blank = new Room();
        assertThrows(CheckerException.class, () -> session.insert(blank), "教室名为空应被拦住");

        Room tooBig = new Room();
        tooBig.roomName = "大礼堂";
        tooBig.capacity = 500;
        CheckerException error = assertThrows(CheckerException.class, () -> session.insert(tooBig));
        assertTrue(error.getMessage().contains("200"), error.getMessage());

        long before = session.count(Query.of(Room.class, session.dialect()));
        assertEquals(1, before, "被拦下的两条都不该落库");
    }

    @Test
    @Order(4)
    @DisplayName("关掉校验后可以直接写入（适合已经清洗过的数据）")
    void validationCanBeDisabled() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);
        ModelSession raw = new ModelSession(provider, "", Dialects.detect(provider.getConnection()), false);
        assertFalse(raw.validating());

        Room tooBig = new Room();
        tooBig.roomName = "大礼堂";
        tooBig.capacity = 500;
        assertEquals(1, raw.insert(tooBig), "不校验时应能写进去");

        assertEquals(2, session.count(Query.of(Room.class, session.dialect())));
        session.deleteById(Room.class, tooBig.roomId);
    }

    @Test
    @Order(5)
    @DisplayName("事务提交：工作单元里的写入全部可见")
    void transactionCommits() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);
        session.transaction(tx -> {
            Room a = new Room();
            a.roomName = "三教 201";
            a.capacity = 40;
            tx.insert(a);
            Room b = new Room();
            b.roomName = "三教 202";
            b.capacity = 40;
            tx.insert(b);
        });
        Query query = Query.of(Room.class, session.dialect()).where("roomName", OperatorType.STARTS_WITH, "三教");
        assertEquals(2, session.count(query));
    }

    @Test
    @Order(6)
    @DisplayName("事务回滚：中途抛异常，之前写的也一并消失")
    void transactionRollsBack() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);
        long before = session.count(Query.of(Room.class, session.dialect()));

        assertThrows(IllegalStateException.class, () -> session.transaction(tx -> {
            Room room = new Room();
            room.roomName = "四教 301";
            room.capacity = 30;
            tx.insert(room);
            throw new IllegalStateException("模拟业务失败");
        }));

        assertEquals(before, session.count(Query.of(Room.class, session.dialect())),
                "回滚后行数应与事务前一致");
        Query query = Query.of(Room.class, session.dialect()).where("roomName", "四教 301");
        assertFalse(session.exists(query), "回滚掉的记录不该查得到");
    }

    @Test
    @Order(7)
    @DisplayName("事务内嵌套调用并入外层事务，由最外层决定成败")
    void nestedTransactionJoinsOuter() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);
        long before = session.count(Query.of(Room.class, session.dialect()));

        assertThrows(IllegalStateException.class, () -> session.transaction(outer -> {
            Room room = new Room();
            room.roomName = "五教 401";
            room.capacity = 30;
            outer.insert(room);
            // 内层自己不会提交：外层抛出后它写的也一起回滚
            outer.transaction(inner -> {
                Room another = new Room();
                another.roomName = "五教 402";
                another.capacity = 30;
                inner.insert(another);
            });
            throw new IllegalStateException("外层失败");
        }));

        assertEquals(before, session.count(Query.of(Room.class, session.dialect())));
    }

    @Test
    @Order(8)
    @DisplayName("执行手写 SQL：占位符按参数顺序绑定，表名前缀自动补齐")
    void executesRawStatement() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider, "api_");

        // 前面几个用例用无前缀会话建过 room，这里换成一张只在前缀会话里出现的表，
        // 免得「已存在」与被测行为混在一起。
        SchemaSync.Report report = session.synchronizeSchema(Step.class);
        assertTrue(report.executed().stream().anyMatch(ddl -> ddl.contains("\"api_step\"")),
                "带前缀时建的表应是 api_step：" + report.executed());

        Step step = new Step();
        step.label = "第一步";
        step.sequence = 1;
        assertEquals(1, session.insert(step));

        // 手写 SQL 要自己拼表名：用 tableName 取实际表名，才能和框架生成的语句落到同一张表。
        String table = session.tableName(Step.class);
        assertEquals("api_step", table);

        SqlStatement statement = new SqlStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE sequence >= ?", List.of(1));
        assertEquals(1L, ((Number) session.queryScalar(statement)).longValue());

        List<String> labels = session.query(
                new SqlStatement("SELECT label FROM \"api_step\" WHERE step_id = ?", List.of(step.stepId)),
                result -> result.getString(1));
        assertEquals(List.of("第一步"), labels);

        // 同一张表在无前缀会话里看不到：前缀是真的落到了表名上，而不只是「查询时忽略」。
        ModelSession plain = ModelSession.sql(provider);
        try (Connection connection = provider.getConnection()) {
            assertFalse(new SchemaSync(provider, plain.dialect(), true).tableExists(connection, "step"),
                    "无前缀会话不该看到 api_step");
        }
    }

    @Test
    @Order(9)
    @DisplayName("元数据改写表名：同一模型可以落到不同的物理表")
    void tableMapperRewritesName() {
        ModelMeta base = ModelMeta.of(Room.class);
        assertEquals("api_room", base.table());

        ModelMeta prefixed = ModelMeta.of(table -> "dev_" + table, Room.class);
        assertEquals("dev_api_room", prefixed.table());
        assertEquals("教室", prefixed.name(), "改写表名不该影响模型名称");

        assertSame(base, ModelMeta.of(Room.class), "原有的元数据缓存不能被改写污染");
        assertEquals("api_room", ModelMeta.of(Room.class).table());
        assertSame(prefixed, ModelMeta.of(table -> "dev_" + table, Room.class));
    }

    @Test
    @Order(10)
    @DisplayName("selectOne 借用查询对象后要还原分页")
    void selectOneRestoresPaging() {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);
        Query query = Query.of(Room.class, session.dialect())
                .orderBy("roomName", SortType.ASC)
                .page(2, 3);

        session.selectOne(query);
        assertEquals(Integer.valueOf(3), query.limit(), "limit 应还原");
        assertEquals(Integer.valueOf(3), query.offset(), "offset 应还原为 (2-1)*3");
    }

    @Test
    @Order(11)
    @DisplayName("批量删除按 1000 分批，避免超出参数上限")
    void batchDeleteSplitsIntoChunks() throws Exception {
        assumeTrue(available, "本机没有可用的 PostgreSQL，跳过");
        ModelSession session = ModelSession.sql(provider);
        session.synchronizeSchema(Room.class);

        List<Long> ids = new ArrayList<>();
        // 放在一个事务里：1200 条逐条提交在 PostgreSQL 上要五十秒左右，
        // 这正是 transaction 存在的意义，顺手也验证了「事务能容纳大批写入」。
        session.transaction(tx -> {
            for (int i = 0; i < 1200; i++) {
                Room room = new Room();
                room.roomName = "批量 " + i;
                room.capacity = 10;
                tx.insert(room);
                ids.add(room.roomId);
            }
        });
        assertEquals(1200, session.count(Query.of(Room.class, session.dialect())
                .where("roomName", OperatorType.STARTS_WITH, "批量")));

        assertEquals(1200, session.deleteByIds(Room.class, ids));
        assertEquals(0, session.count(Query.of(Room.class, session.dialect())
                .where("roomName", OperatorType.STARTS_WITH, "批量")));
    }
}
