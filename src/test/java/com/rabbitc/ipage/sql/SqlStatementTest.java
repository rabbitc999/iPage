package com.rabbitc.ipage.sql;

import com.rabbitc.ipage.annotation.ModelClass;
import com.rabbitc.ipage.annotation.ModelField;
import com.rabbitc.ipage.enums.FieldType;
import com.rabbitc.ipage.enums.OperatorType;
import com.rabbitc.ipage.enums.SortType;
import com.rabbitc.ipage.enums.SqlFunction;
import com.rabbitc.ipage.reflect.ModelMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 SQL 的生成结果。
 * <p>
 * 断言 SQL 文本与参数顺序，而不只是「跑得通」：拼接错误往往在 SQL 层面才有意义。
 */
class SqlStatementTest {

    /**
     * 班级模型。
     */
    @ModelClass(name = "班级", table = "school_class", text = "${className}")
    public static class Sclass {

        @ModelField(column = "class_id", type = FieldType.MODEL_ID, fixed = true)
        Integer sclassId;

        @ModelField(column = "class_name", type = FieldType.STRING, text = "班级名")
        String className;
    }

    /**
     * 学生模型。
     */
    @ModelClass(name = "学生", table = "student", text = "${studentName}")
    public static class Student {

        @ModelField(column = "student_id", type = FieldType.MODEL_ID, fixed = true)
        Integer studentId;

        @ModelField(column = "student_name", type = FieldType.STRING, text = "姓名")
        String studentName;

        @ModelField(column = "class_id", type = FieldType.ASSOCIATION_ID, byModel = Sclass.class, text = "班级")
        Integer sclassId;

        @ModelField(column = "class_id", type = FieldType.ASSOCIATION_MODEL, byModel = Sclass.class, text = "班级")
        Sclass sclass;
    }

    @Test
    @DisplayName("关联字段生成 LEFT JOIN，别名是合法的标识符")
    void joinsAssociation() {
        SqlStatement statement = SqlStatement.select(Query.of(Student.class), true);
        assertTrue(statement.sql().contains("FROM student t_student"), statement.sql());
        assertTrue(statement.sql().contains("LEFT JOIN school_class"), statement.sql());
        assertTrue(statement.sql().contains("= t_school_class.class_id"), statement.sql());
        assertFalse(statement.sql().contains("-"), "别名不应出现连字符");
    }

    @Test
    @DisplayName("条件走占位符，操作符来自封闭枚举")
    void bindsConditionParameters() {
        Query query = Query.of(Student.class)
                .where("studentName", OperatorType.STARTS_WITH, "张")
                .where("studentId", OperatorType.GT, 10);
        SqlStatement statement = SqlStatement.select(query, true);
        assertEquals(2, statement.parameters().size());
        assertEquals("张", statement.parameters().get(0));
        assertEquals(10, ((Number) statement.parameters().get(1)).intValue());
        assertTrue(statement.sql().contains("LIKE CONCAT(?, '%')"), statement.sql());
        assertTrue(statement.sql().contains("> ?"), statement.sql());
    }

    @Test
    @DisplayName("IN 生成对应数量的占位符，空集合退化为恒假")
    void bindsInList() {
        Query query = Query.of(Student.class)
                .where("studentId", OperatorType.IN, List.of(1, 2, 3));
        SqlStatement statement = SqlStatement.select(query, true);
        assertTrue(statement.sql().contains("IN (?, ?, ?)"), statement.sql());
        assertEquals(3, statement.parameters().size());

        Query empty = Query.of(Student.class).where("studentId", OperatorType.IN, List.of());
        SqlStatement falseCondition = SqlStatement.select(empty, true);
        assertTrue(falseCondition.sql().contains("(NULL)"), falseCondition.sql());
        assertTrue(falseCondition.parameters().isEmpty());
    }

    @Test
    @DisplayName("排序与分页按声明顺序出现在末尾")
    void appendsOrderAndPaging() {
        Query query = Query.of(Student.class)
                .where("studentName", "张三")
                .orderBy("studentId", SortType.DESC)
                .page(3, 20);
        SqlStatement statement = SqlStatement.select(query, true);
        assertTrue(statement.sql().endsWith("LIMIT ? OFFSET ?"), statement.sql());
        assertTrue(statement.sql().contains("ORDER BY t_student.student_id DESC"), statement.sql());
        assertEquals(40, ((Number) statement.parameters().get(statement.parameters().size() - 1)).intValue());
        assertEquals(20, ((Number) statement.parameters().get(statement.parameters().size() - 2)).intValue());
    }

    @Test
    @DisplayName("聚合字段产生 GROUP BY，聚合条件进 HAVING")
    void buildsAggregateQuery() {
        Query query = Query.of(AggregateSclass.class)
                .where("classCount", OperatorType.GT, 3);
        SqlStatement statement = SqlStatement.select(query, true);
        assertTrue(query.hasAggregates());
        assertTrue(statement.sql().contains("GROUP BY t_school_class.class_id"), statement.sql());
        assertTrue(statement.sql().contains("COUNT(DISTINCT t_student.class_id)"), statement.sql());
        assertTrue(statement.sql().contains("HAVING"), statement.sql());
    }

    @Test
    @DisplayName("插入语句跳过自增主键与固定字段")
    void buildsInsert() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("student_name", "李四");
        values.put("class_id", 7);
        SqlStatement statement = SqlStatement.insert(ModelMeta.of(Student.class), values);
        assertTrue(statement.sql().startsWith("INSERT INTO student"), statement.sql());
        assertTrue(statement.sql().contains("student_name"), statement.sql());
        assertFalse(statement.sql().contains("student_id"), statement.sql());
        assertEquals("李四", statement.parameters().get(0));
    }

    @Test
    @DisplayName("更新语句以主键结尾")
    void buildsUpdate() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("student_name", "王五");
        SqlStatement statement = SqlStatement.update(ModelMeta.of(Student.class), values, "student_id", 9);
        assertTrue(statement.sql().startsWith("UPDATE student SET"), statement.sql());
        assertTrue(statement.sql().endsWith("WHERE student_id = ?"), statement.sql());
        assertEquals("王五", statement.parameters().get(0));
        assertEquals(9, ((Number) statement.parameters().get(1)).intValue());
    }

    /**
     * 带聚合字段的班级模型：统计班上学生数。
     */
    @ModelClass(name = "班级统计", table = "school_class")
    public static class AggregateSclass {

        @ModelField(column = "class_id", type = FieldType.MODEL_ID, fixed = true)
        Integer sclassId;

        @ModelField(column = "class_name", type = FieldType.STRING, text = "班级名")
        String className;

        @ModelField(column = "class_id", type = FieldType.ASSOCIATION_MODEL, byModel = Student.class, text = "学生")
        Student student;

        @ModelField(column = "class_id", type = FieldType.AGGREGATION, byModel = Student.class,
                function = SqlFunction.COUNT, text = "人数")
        Integer classCount;
    }

    /**
     * 表名与 SQL 保留字同名，用来验证别名不会退化成保留字。
     */
    @ModelClass(name = "登录用户", table = "user")
    public static class User {

        @ModelField(column = "user_id", type = FieldType.MODEL_ID, fixed = true)
        Integer userId;

        @ModelField(column = "user_name", type = FieldType.STRING, text = "用户名")
        String userName;
    }

    @Test
    @DisplayName("表名撞上 SQL 保留字时，别名也不能是保留字")
    void avoidsReservedWordAlias() {
        Query query = Query.of(ModelMeta.of(User.class), com.rabbitc.ipage.dialect.PostgresDialect.INSTANCE);
        SqlStatement statement = SqlStatement.select(query, true, com.rabbitc.ipage.dialect.PostgresDialect.INSTANCE);
        // PostgreSQL 会把裸 user 解析成 CURRENT_USER，写成 user."user_id" 直接语法错误
        assertTrue(statement.sql().contains("FROM \"user\" t_user"), statement.sql());
        assertTrue(statement.sql().contains("t_user.\"user_id\""), statement.sql());
    }

    @Test
    @DisplayName("带聚合时 GROUP BY 覆盖所有非聚合选择列")
    void groupsAllNonAggregateColumns() {
        String sql = SqlStatement.select(Query.of(AggregateSclass.class), true).sql();
        String groupBy = sql.substring(sql.indexOf("GROUP BY ") + "GROUP BY ".length());
        if (groupBy.contains(" HAVING")) {
            groupBy = groupBy.substring(0, groupBy.indexOf(" HAVING"));
        }
        // 关联表 t_student 的列也被 SELECT 出来，必须同样出现在 GROUP BY 里，
        // 否则 PostgreSQL 报「字段必须出现在 GROUP BY 子句里」
        assertTrue(groupBy.contains("t_school_class.class_id"), sql);
        assertTrue(groupBy.contains("t_student.student_name"), sql);
        assertTrue(groupBy.contains("t_student.class_id"), sql);
    }
}
